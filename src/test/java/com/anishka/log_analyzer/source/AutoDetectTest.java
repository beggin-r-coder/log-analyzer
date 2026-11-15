package com.anishka.log_analyzer.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.anishka.log_analyzer.entity.LogRecord;
import com.anishka.log_analyzer.repository.LogRecordRepository;

/** Column roles worked out from the data, log tables found in a database, Excel and CSV files as sources. */
@SpringBootTest
class AutoDetectTest {

	@Autowired ColumnDetector detector;
	@Autowired DatabasePoller poller;
	@Autowired SpreadsheetPoller sheets;
	@Autowired LogSourceService service;
	@Autowired LogSourceRepository sources;
	@Autowired SourceFileStateRepository fileStates;
	@Autowired LogRecordRepository logs;

	@TempDir Path dir;

	static final String[] COMPS = { "EAIObjMgr_enu", "SCCObjMgr_enu", "WfProcMgr" };
	static final String[] MSGS = { "Outbound call to core banking completed in 120 ms", "SBL-EAI-04308: Operation GetBalance failed: Read timed out",
			"SQL statement took 4.2 seconds to execute", "User session started for agent", "SBL-DAT-00398: Field not found in business component" };
	static final String[] LEVELS = { "INFO", "ERROR", "WARN", "INFO", "ERROR" };

	@BeforeEach
	void clean() {
		fileStates.deleteAll();
		sources.deleteAll();
		logs.deleteAll();
	}

	@Test
	void columnsWithMeaninglessNamesAreRecognisedFromTheirValues() {
		List<String> names = List.of("C1", "C2", "C3", "C4", "C5", "C6", "C7");
		List<List<Object>> rows = new ArrayList<>();
		LocalDateTime t = LocalDateTime.now().minusHours(1);
		for (int i = 0; i < 40; i++) {
			rows.add(List.of(1000L + i, COMPS[i % 3], t.plusSeconds(i * 7).toString().replace('T', ' '), MSGS[i % 5],
					LEVELS[i % 5], String.format("%016x:0", 0x257c41c8c00L + i * 977L), "crmapp0" + (i % 2 + 1)));
		}
		ColumnDetector.Detection d = detector.detect(names, rows);
		assertEquals("C1", d.mapping().get("idColumn"));
		assertEquals("C2", d.mapping().get("serviceColumn"));
		assertEquals("C3", d.mapping().get("tsColumn"));
		assertEquals("C4", d.mapping().get("messageColumn"));
		assertEquals("C5", d.mapping().get("levelColumn"));
		assertEquals("C6", d.mapping().get("traceColumn"));
		assertEquals("C7", d.mapping().get("hostColumn"));
		assertTrue(d.logLikeness() >= 70, "looks like logs: " + d.logLikeness());
		assertTrue(d.reasons().get("tsColumn").contains("dates/times"));
	}

	@Test
	void aColumnOfCompleteLogLinesIsReadAsRawLines() {
		List<List<Object>> rows = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			rows.add(List.of((long) i + 1, "2026-11-12 10:15:" + String.format("%02d", i) + ".123 ERROR 4242 --- [main] c.b.PaymentService : Payment " + i + " failed"));
		}
		ColumnDetector.Detection d = detector.detect(List.of("N", "LINE_DATA"), rows);
		assertEquals("LINE_DATA", d.mapping().get("rawColumn"));
		assertFalse(d.mapping().containsKey("messageColumn"));
	}

	@Test
	void numericSiebelSeveritiesAreALevelColumn() {
		List<List<Object>> rows = new ArrayList<>();
		for (int i = 0; i < 30; i++) rows.add(List.of(String.valueOf(i % 5 + 1), MSGS[i % 5], "2026-11-12 10:15:00"));
		ColumnDetector.Detection d = detector.detect(List.of("A", "B", "D"), rows);
		assertEquals("A", d.mapping().get("levelColumn"));
	}

	@Test
	void findsTheLogTableInADatabaseAndSavesItWithoutAnyMapping() throws Exception {
		String url = "jdbc:h2:mem:disc_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
		try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
			st.execute("CREATE TABLE CUSTOMERS (CUST_ID INT PRIMARY KEY, FULL_NAME VARCHAR(80), CITY VARCHAR(40))");
			st.execute("INSERT INTO CUSTOMERS VALUES (1, 'Asha Rao', 'Pune'), (2, 'Vikram Shah', 'Mumbai'), (3, 'Meera Iyer', 'Chennai')");
			st.execute("CREATE TABLE T_EVT (K BIGINT AUTO_INCREMENT PRIMARY KEY, WHEN_AT TIMESTAMP, SRC VARCHAR(64), LVL VARCHAR(10), TXT VARCHAR(2000))");
			StringBuilder ins = new StringBuilder("INSERT INTO T_EVT (WHEN_AT, SRC, LVL, TXT) VALUES ");
			for (int i = 0; i < 30; i++) {
				ins.append(i == 0 ? "" : ",").append("(DATEADD('SECOND', ").append(i - 600).append(", CURRENT_TIMESTAMP), '").append(COMPS[i % 3])
						.append("', '").append(LEVELS[i % 5]).append("', '").append(MSGS[i % 5]).append("')");
			}
			st.execute(ins.toString());

			SourceForm conn = new SourceForm("crm", "DATABASE", true, null, null, null, 1, null, null, null, null, null, null, null, url,
					null, null, null, null, null, null, null, null, null, 15);
			Map<String, Object> found = service.discover(conn, null);
			assertEquals(true, found.get("ok"), String.valueOf(found.get("error")));
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> tables = (List<Map<String, Object>>) found.get("tables");
			assertEquals("T_EVT", tables.get(0).get("table"), "the log table comes first: " + tables);
			assertTrue(tables.stream().noneMatch(t -> "CUSTOMERS".equals(t.get("table"))), "customer table is not a log");

			Map<String, Object> saved = service.create(new SourceForm("crm", "DATABASE", true, null, null, null, 1, null, null, null, null,
					null, null, null, url, "T_EVT", null, null, null, null, null, null, null, null, 15));
			assertEquals("WHEN_AT", saved.get("tsColumn"));
			assertEquals("TXT", saved.get("messageColumn"));
			assertEquals("LVL", saved.get("levelColumn"));
			assertEquals("SRC", saved.get("serviceColumn"));
			assertEquals("K", saved.get("idColumn"));
			assertEquals(30, poller.poll(sources.findById(((Number) saved.get("id")).longValue()).orElseThrow()));
		}
	}

	@Test
	void readsAnExcelWorkbookWithATitleRowAndPicksUpNewRows() throws Exception {
		Path xlsx = dir.resolve("Siebel errors.xlsx");
		LocalDateTime t0 = LocalDateTime.now().minusMinutes(30);
		writeWorkbook(xlsx, t0, 25);

		Map<String, Object> test = service.test(form(xlsx), null);
		assertEquals(true, test.get("ok"), String.valueOf(test.get("error")));
		assertEquals("Errors", test.get("sheet"), "the sheet with the log, not the notes sheet");
		@SuppressWarnings("unchecked")
		Map<String, String> m = (Map<String, String>) test.get("suggested");
		assertEquals("Logged on", m.get("tsColumn"));
		assertEquals("Details", m.get("messageColumn"));
		assertEquals("Sev", m.get("levelColumn"));

		Map<String, Object> saved = service.create(form(xlsx));
		assertEquals("Siebel errors.xlsx", saved.get("name"));
		LogSource s = sources.findById(((Number) saved.get("id")).longValue()).orElseThrow();
		assertEquals(25, sheets.poll(s));
		List<LogRecord> r = logs.findAll();
		assertTrue(r.stream().anyMatch(x -> "ERROR".equals(x.getLevel()) && x.getMessage().contains("SBL-EAI-04308")));
		assertTrue(r.stream().allMatch(x -> x.getTimestamp().isAfter(t0.minusSeconds(1))), "timestamps come from the sheet");

		assertEquals(0, sheets.poll(sources.findById(s.getId()).orElseThrow()), "unchanged file");
		writeWorkbook(xlsx, t0, 31);
		Files.setLastModifiedTime(xlsx, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
		assertEquals(6, sheets.poll(sources.findById(s.getId()).orElseThrow()), "only the added rows");
	}

	@Test
	void readsASemicolonCsvExport() throws Exception {
		Path csv = dir.resolve("export.csv");
		StringBuilder b = new StringBuilder("﻿Zeitpunkt;Komponente;Stufe;Meldung\n");
		for (int i = 0; i < 12; i++) {
			b.append("12-11-2026 10:").append(String.format("%02d", i)).append(":00;").append(COMPS[i % 3]).append(';').append(LEVELS[i % 5])
					.append(";\"").append(MSGS[i % 5].replace("seconds", "seconds; slow")).append("\"\n");
		}
		Files.writeString(csv, b, StandardCharsets.UTF_8);
		Map<String, Object> saved = service.create(form(csv));
		assertEquals("Zeitpunkt", saved.get("tsColumn"));
		assertEquals("Meldung", saved.get("messageColumn"));
		assertEquals("Komponente", saved.get("serviceColumn"));
		assertEquals(12, sheets.poll(sources.findById(((Number) saved.get("id")).longValue()).orElseThrow()));
		assertTrue(logs.findAll().stream().anyMatch(x -> x.getMessage().endsWith("seconds; slow to execute")));
		Files.writeString(csv, "12-11-2026 11:00:00;WfProcMgr;ERROR;SBL-BPR-00162: Error invoking service\n", StandardOpenOption.APPEND);
		Files.setLastModifiedTime(csv, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
		assertEquals(1, sheets.poll(sources.findById(((Number) saved.get("id")).longValue()).orElseThrow()));
	}

	private static SourceForm form(Path file) {
		return new SourceForm(null, "FILE", true, file.toString(), null, null, 1, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, 15);
	}

	private static void writeWorkbook(Path f, LocalDateTime t0, int rows) throws Exception {
		try (XSSFWorkbook wb = new XSSFWorkbook()) {
			Sheet notes = wb.createSheet("Read me");
			notes.createRow(0).createCell(0).setCellValue("Exported from Siebel for the weekly review");
			Sheet s = wb.createSheet("Errors");
			s.createRow(0).createCell(0).setCellValue("Siebel CRM – error report");
			Row h = s.createRow(2);
			String[] head = { "Logged on", "Component", "Sev", "Details", "Server" };
			for (int i = 0; i < head.length; i++) h.createCell(i).setCellValue(head[i]);
			CellStyle date = wb.createCellStyle();
			date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd-mm-yyyy hh:mm:ss"));
			for (int i = 0; i < rows; i++) {
				Row r = s.createRow(3 + i);
				r.createCell(0).setCellValue(t0.plusSeconds(i * 30L));
				r.getCell(0).setCellStyle(date);
				r.createCell(1).setCellValue(COMPS[i % 3]);
				r.createCell(2).setCellValue(i % 5 == 1 || i % 5 == 4 ? 1 : 3);
				r.createCell(3).setCellValue(MSGS[i % 5]);
				r.createCell(4).setCellValue("crmapp0" + (i % 2 + 1));
			}
			try (OutputStream out = Files.newOutputStream(f)) {
				wb.write(out);
			}
		}
	}
}
