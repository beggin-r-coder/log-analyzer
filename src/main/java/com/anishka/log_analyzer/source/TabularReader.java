package com.anishka.log_analyzer.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Reads Excel workbooks (.xlsx, .xlsm, .xls) and delimited text (.csv, .tsv, .txt) into a header and rows of
 * typed values. The header row is found automatically (title rows above the table are skipped) and CSV
 * delimiters and encodings are sniffed, so exports from Siebel, Excel or other tools work as they are.
 */
public final class TabularReader {

	public static final int MAX_ROWS = 200_000;

	/** One table: column names and the rows below them. */
	public record Table(String sheet, List<String> sheets, List<String> names, List<List<Object>> rows) {
	}

	private TabularReader() {
	}

	public static boolean supports(Path p) {
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
		return n.endsWith(".xlsx") || n.endsWith(".xlsm") || n.endsWith(".xls") || n.endsWith(".csv") || n.endsWith(".tsv") || n.endsWith(".txt");
	}

	static boolean isExcel(Path p) {
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
		return n.endsWith(".xlsx") || n.endsWith(".xlsm") || n.endsWith(".xls");
	}

	/**
	 * @param sheet the worksheet to read, or null/blank to pick the one that looks most like a log
	 * @param chooser scores a candidate table (higher is better) when no sheet is named
	 */
	public static Table read(Path file, String sheet, java.util.function.ToIntFunction<Table> chooser) throws IOException {
		if (!Files.isRegularFile(file)) throw new IOException("File not found or not reachable from this computer: " + file);
		if (!supports(file)) throw new IOException("Choose an Excel workbook (.xlsx, .xls) or a CSV file");
		return isExcel(file) ? excel(file, sheet, chooser) : csv(file);
	}

	// ------------------------------------------------------------------ Excel

	private static Table excel(Path file, String wanted, java.util.function.ToIntFunction<Table> chooser) throws IOException {
		// read through a stream so a workbook that is open in Excel can still be read
		try (InputStream in = Files.newInputStream(file); Workbook wb = WorkbookFactory.create(in)) {
			List<String> sheets = new ArrayList<>();
			for (int i = 0; i < wb.getNumberOfSheets(); i++) sheets.add(wb.getSheetName(i));
			if (wanted != null && !wanted.isBlank()) {
				Sheet s = wb.getSheet(wanted.trim());
				if (s == null) throw new IOException("The workbook has no sheet named \"" + wanted.trim() + "\" (sheets: " + String.join(", ", sheets) + ")");
				return sheet(s, sheets, MAX_ROWS);
			}
			Table best = null;
			int bestScore = Integer.MIN_VALUE;
			for (int i = 0; i < wb.getNumberOfSheets(); i++) {
				if (wb.isSheetHidden(i) || wb.isSheetVeryHidden(i)) continue;
				Table sample = sheet(wb.getSheetAt(i), sheets, 200);
				if (sample.names().isEmpty()) continue;
				int score = chooser == null ? sample.rows().size() : chooser.applyAsInt(sample);
				if (score > bestScore) {
					bestScore = score;
					best = sample;
				}
			}
			if (best == null) throw new IOException("The workbook has no sheet with a table in it");
			return sheet(wb.getSheet(best.sheet()), sheets, MAX_ROWS);
		}
	}

	private static Table sheet(Sheet s, List<String> sheets, int maxRows) {
		List<List<Object>> grid = new ArrayList<>();
		int last = Math.min(s.getLastRowNum(), maxRows + 50);
		for (int r = s.getFirstRowNum(); r <= last && r >= 0; r++) {
			Row row = s.getRow(r);
			List<Object> values = new ArrayList<>();
			if (row != null) {
				for (int c = 0; c < Math.min(Math.max(row.getLastCellNum(), 0), 200); c++) values.add(cell(row.getCell(c)));
			}
			grid.add(values);
		}
		return table(s.getSheetName(), sheets, grid, maxRows);
	}

	private static Object cell(Cell c) {
		if (c == null) return null;
		CellType t = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
		switch (t) {
		case NUMERIC:
			if (DateUtil.isCellDateFormatted(c)) {
				LocalDateTime d = c.getLocalDateTimeCellValue();
				return d;
			}
			double v = c.getNumericCellValue();
			return v == Math.rint(v) && Math.abs(v) < 1e15 ? (Object) (long) v : (Object) v;
		case STRING:
			String s = c.getStringCellValue();
			return s == null || s.isBlank() ? null : s.strip();
		case BOOLEAN:
			return String.valueOf(c.getBooleanCellValue());
		default:
			return null;
		}
	}

	// ------------------------------------------------------------------ CSV

	private static Table csv(Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		if (bytes.length > 512L * 1024 * 1024) throw new IOException("The file is too large to read as a table");
		String text = decode(bytes);
		char delim = sniff(text);
		List<List<Object>> grid = new ArrayList<>();
		List<Object> row = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean quoted = false, wasQuoted = false;
		for (int i = 0; i < text.length() && grid.size() <= MAX_ROWS + 50; i++) {
			char ch = text.charAt(i);
			if (quoted) {
				if (ch == '"') {
					if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
						cell.append('"');
						i++;
					} else {
						quoted = false;
					}
				} else {
					cell.append(ch);
				}
			} else if (ch == '"' && cell.length() == 0) {
				quoted = true;
				wasQuoted = true;
			} else if (ch == delim) {
				row.add(csvValue(cell, wasQuoted));
				cell.setLength(0);
				wasQuoted = false;
			} else if (ch == '\n' || ch == '\r') {
				if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
				row.add(csvValue(cell, wasQuoted));
				grid.add(row);
				row = new ArrayList<>();
				cell.setLength(0);
				wasQuoted = false;
			} else {
				cell.append(ch);
			}
		}
		if (cell.length() > 0 || !row.isEmpty()) {
			row.add(csvValue(cell, wasQuoted));
			grid.add(row);
		}
		return table(null, List.of(), grid, MAX_ROWS);
	}

	private static Object csvValue(StringBuilder cell, boolean quoted) {
		String s = quoted ? cell.toString() : cell.toString().strip();
		if (s.isBlank()) return null;
		if (!quoted && s.matches("-?\\d{1,15}")) return Long.parseLong(s);
		return s;
	}

	private static String decode(byte[] b) {
		if (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) {
			return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
		}
		if (b.length >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
		if (b.length >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
		try {
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
		} catch (CharacterCodingException e) {
			return new String(b, Charset.forName("windows-1252")); // Excel "CSV" on Windows
		}
	}

	/** The delimiter that splits the first lines into the same, largest number of fields. */
	static char sniff(String text) {
		String[] lines = text.lines().limit(20).filter(l -> !l.isBlank()).toArray(String[]::new);
		char best = ',';
		double bestScore = 0;
		for (char d : new char[] { ',', ';', '\t', '|' }) {
			int[] counts = new int[lines.length];
			for (int i = 0; i < lines.length; i++) counts[i] = count(lines[i], d);
			if (counts.length == 0) continue;
			int mode = mode(counts);
			if (mode == 0) continue;
			long same = java.util.Arrays.stream(counts).filter(c -> c == mode).count();
			double score = mode * ((double) same / counts.length);
			if (score > bestScore) {
				bestScore = score;
				best = d;
			}
		}
		return best;
	}

	private static int count(String line, char d) {
		int n = 0;
		boolean q = false;
		for (char c : line.toCharArray()) {
			if (c == '"') q = !q;
			else if (c == d && !q) n++;
		}
		return n;
	}

	private static int mode(int[] a) {
		java.util.Map<Integer, Integer> m = new java.util.HashMap<>();
		int best = 0, bestN = 0;
		for (int v : a) {
			int n = m.merge(v, 1, Integer::sum);
			if (n > bestN || (n == bestN && v > best)) {
				best = v;
				bestN = n;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ header detection

	/**
	 * Finds the header row: among the first rows, the one with the most filled text cells that is followed by
	 * data. Title rows, notes and empty rows above it are skipped; empty columns are dropped.
	 */
	static Table table(String sheet, List<String> sheets, List<List<Object>> grid, int maxRows) {
		int header = -1, bestFilled = 0;
		for (int r = 0; r < Math.min(grid.size(), 30); r++) {
			List<Object> row = grid.get(r);
			int text = 0, filled = 0;
			for (Object o : row) {
				if (o == null) continue;
				filled++;
				if (o instanceof String s && Timestamps.parse(s) == null && !s.matches("-?[\\d.,]+") && s.length() <= 80) text++;
			}
			if (filled >= 1 && text == filled && filled > bestFilled && r + 1 < grid.size()) {
				header = r;
				bestFilled = filled;
			}
		}
		List<String> names = new ArrayList<>();
		List<List<Object>> rows = new ArrayList<>();
		if (grid.isEmpty()) return new Table(sheet, sheets, names, rows);
		int width = 0;
		for (List<Object> r : grid) width = Math.max(width, r.size());
		List<Object> head = header >= 0 ? grid.get(header) : List.of();
		List<Integer> keep = new ArrayList<>();
		for (int c = 0; c < width; c++) {
			boolean any = c < head.size() && head.get(c) != null;
			for (int r = header + 1; !any && r < Math.min(grid.size(), header + 500); r++) {
				any = c < grid.get(r).size() && grid.get(r).get(c) != null;
			}
			if (!any) continue;
			keep.add(c);
			String n = c < head.size() && head.get(c) != null ? head.get(c).toString().strip() : "Column " + (char) ('A' + Math.min(c, 25));
			while (names.contains(n)) n = n + "_" + (c + 1);
			names.add(n);
		}
		for (int r = header + 1; r < grid.size() && rows.size() < maxRows; r++) {
			List<Object> src = grid.get(r);
			List<Object> out = new ArrayList<>(keep.size());
			boolean any = false;
			for (int c : keep) {
				Object v = c < src.size() ? src.get(c) : null;
				any |= v != null;
				out.add(v);
			}
			if (any) rows.add(out);
		}
		return new Table(sheet, sheets, names, rows);
	}
}
