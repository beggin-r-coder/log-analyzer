package com.anishka.log_analyzer.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.anishka.log_analyzer.parser.LogFormat;
import com.anishka.log_analyzer.parser.ParsedLog;
import com.anishka.log_analyzer.service.LogIngestionService;

/**
 * Reads Excel workbooks and CSV files as log sources. The file is re-read when it changes; only rows added
 * since the last read are stored (if the file got shorter it was replaced, so it is read again from the top).
 * The sheet and column roles are found automatically unless the user chose them.
 */
@Service
public class SpreadsheetPoller {

	private static final Logger log = LoggerFactory.getLogger(SpreadsheetPoller.class);
	static final int MAX_EVENTS_PER_PASS = 20_000;

	private final LogSourceRepository sources;
	private final LogIngestionService ingestion;
	private final ColumnDetector detector;
	private final RowMapper rowMapper;

	public SpreadsheetPoller(LogSourceRepository sources, LogIngestionService ingestion, ColumnDetector detector, RowMapper rowMapper) {
		this.sources = sources;
		this.ingestion = ingestion;
		this.detector = detector;
		this.rowMapper = rowMapper;
	}

	@Scheduled(fixedDelayString = "${app.sources.db-tick-ms:5000}", initialDelayString = "${app.sources.initial-delay-ms:4000}")
	public void pollAll() {
		LocalDateTime now = LocalDateTime.now();
		for (LogSource s : sources.findByTypeAndEnabledTrue(LogSource.FILE)) {
			if (s.getLastRunAt() == null || s.getLastRunAt().plusSeconds(Math.max(5, s.getPollSeconds())).isBefore(now)) {
				poll(s);
			}
		}
	}

	/** Reads rows added since the last pass. Returns the number of events stored. */
	public synchronized int poll(LogSource src) {
		LocalDateTime runAt = LocalDateTime.now();
		int stored = 0;
		String error = null;
		sources.findById(src.getId()).ifPresent(fresh -> {
			src.setWatermarkId(fresh.getWatermarkId());
			src.setWatermarkTs(fresh.getWatermarkTs());
		});
		try {
			Path file = Path.of(src.getPath().trim());
			if (!Files.isRegularFile(file)) throw new IOException("File not found or not reachable from this computer: " + file);
			LocalDateTime modified = LocalDateTime.ofInstant(Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()), ZoneId.systemDefault());
			int done = src.getWatermarkId() == null || !src.getWatermarkId().matches("\\d+") ? 0 : Integer.parseInt(src.getWatermarkId());
			if (src.getWatermarkTs() == null || !modified.equals(src.getWatermarkTs()) || done == 0) {
				TabularReader.Table t = read(src, file);
				if (t.rows().size() < done) done = 0; // replaced by a shorter file
				Map<String, Integer> index = index(t.names());
				List<ParsedLog> batch = new ArrayList<>();
				int r = done;
				for (; r < t.rows().size() && batch.size() < MAX_EVENTS_PER_PASS; r++) {
					List<Object> row = t.rows().get(r);
					batch.addAll(rowMapper.map(src, name -> {
						Integer i = index.get(name.trim().toLowerCase(Locale.ROOT));
						return i == null || i >= row.size() ? null : row.get(i);
					}, LogFormat.SPREADSHEET));
				}
				if (!batch.isEmpty()) ingestion.save(batch);
				stored = batch.size();
				// a large file is read over several passes: keep the old timestamp until everything is in
				sources.recordWatermark(src.getId(), r >= t.rows().size() ? modified : null, String.valueOf(r));
				src.setWatermarkId(String.valueOf(r));
			}
		} catch (IOException | RuntimeException e) {
			error = e.getMessage() == null ? e.toString() : e.getMessage();
			log.debug("File source {} failed: {}", src.getName(), e.toString());
		}
		sources.recordRun(src.getId(), runAt, error == null ? LocalDateTime.now() : null,
				error == null ? null : error.length() > 1000 ? error.substring(0, 999) : error, stored, 1);
		return stored;
	}

	private TabularReader.Table read(LogSource src, Path file) throws IOException {
		TabularReader.Table t = TabularReader.read(file, src.getQueryText(), this::score);
		if (src.isAutoColumns() && !RowMapper.notBlank(src.getMessageColumn()) && !RowMapper.notBlank(src.getRawColumn())) {
			DatabasePoller.applyMapping(src, detector.detect(t.names(), t.rows().subList(0, Math.min(200, t.rows().size()))).mapping());
		}
		Map<String, Integer> index = index(t.names());
		for (String col : new String[] { src.getTsColumn(), src.getMessageColumn(), src.getRawColumn(), src.getLevelColumn() }) {
			if (RowMapper.notBlank(col) && !index.containsKey(col.trim().toLowerCase(Locale.ROOT))) {
				throw new IOException("Column \"" + col + "\" is not in " + (t.sheet() == null ? "the file" : "sheet " + t.sheet()) + " any more");
			}
		}
		return t;
	}

	private int score(TabularReader.Table t) {
		return detector.detect(t.names(), t.rows()).logLikeness() * 1000 + Math.min(999, t.rows().size());
	}

	/** First rows of the file with each column's role worked out, for the Log sources screen. */
	public Map<String, Object> preview(LogSource src) throws IOException {
		Path file = Path.of(src.getPath() == null ? "" : src.getPath().trim());
		TabularReader.Table t = TabularReader.read(file, src.getQueryText(), this::score);
		ColumnDetector.Detection d = detector.detect(t.names(), t.rows().subList(0, Math.min(200, t.rows().size())));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("product", TabularReader.isExcel(file) ? "Excel workbook" : "CSV file");
		out.put("sheet", t.sheet());
		out.put("sheets", t.sheets());
		out.put("totalRows", t.rows().size());
		out.put("columns", t.names());
		out.put("rows", DatabasePoller.display(t.rows(), 10));
		out.put("suggested", d.mapping());
		out.put("detected", DatabasePoller.detection(d));
		return out;
	}

	private static Map<String, Integer> index(List<String> names) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i < names.size(); i++) m.putIfAbsent(names.get(i).trim().toLowerCase(Locale.ROOT), i);
		return m;
	}
}
