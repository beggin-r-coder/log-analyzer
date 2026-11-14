package com.anishka.log_analyzer.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.anishka.log_analyzer.entity.LogRecord;
import com.anishka.log_analyzer.repository.LogRecordRepository;

/** Folder tailing (Siebel files growing, rotating, history) and reading new rows from a database. */
@SpringBootTest
class LogSourcesTest {

	@Autowired FolderTailer tailer;
	@Autowired DatabasePoller poller;
	@Autowired LogSourceService service;
	@Autowired LogSourceRepository sources;
	@Autowired SourceFileStateRepository fileStates;
	@Autowired LogRecordRepository logs;

	@TempDir Path dir;

	static final String HEADER = "2021 2026-11-12 10:15:01 0000-00-00 00:00:00 +0000 00000000 001 003f 0001 09 SCCObjMgr_enu 2097155 5360 4940 D:\\siebel\\log\\SCCObjMgr_enu_0020_2097155.log 8.1.1.11 [23030] ENU";

	static String ev(String type, String sub, int sev, String msg) {
		return type + "\t" + sub + "\t" + sev + "\t0000000257c41c8c:0\t2026-11-12 10:15:02\t" + msg;
	}

	@BeforeEach
	void clean() {
		fileStates.deleteAll();
		sources.deleteAll();
		logs.deleteAll();
	}

	private LogSource folder(int backfillDays) {
		LogSource s = new LogSource();
		s.setName("test folder");
		s.setType(LogSource.FOLDER);
		s.setPath(dir.toString());
		s.setBackfillDays(backfillDays);
		return sources.save(s);
	}

	private static void write(Path f, String... lines) throws Exception {
		Files.writeString(f, String.join("\n", lines) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	private static void idle(Path f) throws Exception {
		Files.setLastModifiedTime(f, FileTime.from(Instant.now().minus(30, ChronoUnit.SECONDS)));
	}

	private List<LogRecord> stored() {
		return logs.findAll().stream().sorted(Comparator.comparing(LogRecord::getId)).toList();
	}

	@Test
	void followsAGrowingSiebelLogWithoutCuttingMultiLineEvents() throws Exception {
		Path f = dir.resolve("SCCObjMgr_enu_0020_2097155.log");
		write(f, HEADER, ev("ObjMgrSessionInfo", "ObjMgrLogin", 3, "Login name : AGENT01"),
				ev("GenericLog", "GenericError", 1, "SBL-DAT-00382: duplicate"),
				ev("SQLParseAndExecute", "Statement", 4, "SELECT statement with ID: 1"), "  SELECT T1.ROW_ID");
		LogSource src = folder(1);

		assertEquals(3, tailer.poll(src), "header + 2 events; the SQL event may still be growing");
		// the rest of the SQL statement and a new event arrive
		write(f, "  FROM SIEBEL.S_SRV_REQ T1", ev("GenericLog", "GenericWarning", 2, "slow"));
		assertEquals(1, tailer.poll(src), "the SQL event is now complete; the newest one is held back");
		LogRecord sql = stored().get(3);
		assertTrue(sql.getMessage().contains("SELECT T1.ROW_ID") && sql.getMessage().contains("FROM SIEBEL.S_SRV_REQ T1"), sql.getMessage());
		assertEquals("SCCObjMgr_enu", sql.getService(), "component from the header, even when reading from the middle");

		idle(f);
		tailer.poll(src); // size unchanged and file idle: the last event is complete
		assertEquals(5, stored().size());
		assertEquals("WARN", stored().get(4).getLevel());
		assertEquals(0, tailer.poll(src), "nothing new, nothing read twice");

		LogSource after = sources.findById(src.getId()).orElseThrow();
		assertEquals(5, after.getEventsIngested());
		assertEquals(1, after.getFilesTracked());
		assertNull(after.getLastError());
	}

	@Test
	void rotatedFilesAreReadAgainAndOldFilesOnlyFromNow() throws Exception {
		Path old = dir.resolve("app-old.log");
		write(old, "2026-11-01 10:00:00,000 ERROR [main] com.x.Y - old failure");
		Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(3, ChronoUnit.DAYS)));
		Path cur = dir.resolve("app.log");
		write(cur, "2026-11-12 10:00:00,000 INFO [main] com.x.Y - first", "2026-11-12 10:00:01,000 INFO [main] com.x.Y - second");
		idle(cur);
		LogSource src = folder(1);
		tailer.poll(src);
		tailer.poll(src);
		assertEquals(2, stored().size(), "files older than the history window are skipped");
		assertEquals("app", stored().get(0).getService(), "service taken from the file name");

		write(old, "2026-11-12 10:05:00,000 ERROR [main] com.x.Y - new line in old file");
		idle(old);
		tailer.poll(src);
		tailer.poll(src);
		assertEquals(3, stored().size(), "…but their new lines are read");

		Files.writeString(cur, "2026-11-12 11:00:00,000 WARN [main] com.x.Y - after rotation\n", StandardCharsets.UTF_8);
		idle(cur);
		tailer.poll(src);
		tailer.poll(src);
		assertEquals("after rotation", stored().get(stored().size() - 1).getMessage());
	}

	@Test
	void missingFolderIsReportedNotThrown() {
		LogSource s = folder(1);
		s.setPath(dir.resolve("nope").toString());
		sources.save(s);
		assertEquals(0, tailer.poll(s));
		assertTrue(sources.findById(s.getId()).orElseThrow().getLastError().contains("not found"));
	}

	@Test
	void readsOnlyNewRowsFromADatabaseTable() throws Exception {
		String url = "jdbc:h2:mem:crm_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
		try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
			st.execute("CREATE TABLE CRM_APP_LOG (LOG_ID BIGINT AUTO_INCREMENT PRIMARY KEY, LOG_TIME TIMESTAMP, SEVERITY VARCHAR(10), COMPONENT VARCHAR(64), SARM_ID VARCHAR(40), MESSAGE VARCHAR(2000))");
			st.execute("INSERT INTO CRM_APP_LOG (LOG_TIME, SEVERITY, COMPONENT, SARM_ID, MESSAGE) VALUES "
					+ "(CURRENT_TIMESTAMP, '3', 'EAIObjMgr_enu', 'abc:0', 'Outbound call completed'),"
					+ "(CURRENT_TIMESTAMP, '1', 'EAIObjMgr_enu', 'def:0', 'SBL-EAI-04308: Operation GetBalance failed: Read timed out'),"
					+ "(CURRENT_TIMESTAMP, 'WARNING', 'SCCObjMgr_enu', 'ghi:0', 'SQL statement took 4.2 seconds')");

			LogSource s = new LogSource();
			s.setName("CRM log table");
			s.setType(LogSource.DATABASE);
			s.setJdbcUrl(url);
			s.setQueryText("CRM_APP_LOG");
			Map<String, Object> preview = poller.preview(s, null);
			@SuppressWarnings("unchecked")
			Map<String, String> m = (Map<String, String>) preview.get("suggested");
			assertEquals("LOG_TIME", m.get("tsColumn"));
			assertEquals("SEVERITY", m.get("levelColumn"));
			assertEquals("COMPONENT", m.get("serviceColumn"));
			assertEquals("MESSAGE", m.get("messageColumn"));
			assertEquals("SARM_ID", m.get("traceColumn"));
			assertEquals("LOG_ID", m.get("idColumn"));

			s.setTsColumn("LOG_TIME"); s.setLevelColumn("SEVERITY"); s.setServiceColumn("COMPONENT");
			s.setMessageColumn("MESSAGE"); s.setTraceColumn("SARM_ID"); s.setIdColumn("LOG_ID");
			s = sources.save(s);
			assertEquals(3, poller.poll(s));
			List<LogRecord> r = stored();
			assertEquals("INFO", r.get(0).getLevel(), "numeric Siebel severity 3");
			assertEquals("ERROR", r.get(1).getLevel(), "severity 1 with an SBL code");
			assertEquals("Siebel EAI & Integration", r.get(1).getErrorCategory());
			assertEquals("def:0", r.get(1).getTraceId());
			assertEquals("WARN", r.get(2).getLevel());

			assertEquals(0, poller.poll(sources.findById(s.getId()).orElseThrow()), "no new rows");
			st.execute("INSERT INTO CRM_APP_LOG (LOG_TIME, SEVERITY, COMPONENT, SARM_ID, MESSAGE) VALUES (CURRENT_TIMESTAMP, 'ERROR', 'WfProcMgr', 'x:0', 'SBL-BPR-00162: Error invoking service')");
			assertEquals(1, poller.poll(sources.findById(s.getId()).orElseThrow()));
			assertEquals("4", sources.findById(s.getId()).orElseThrow().getWatermarkId());
		}
	}

	@Test
	void passwordsAreStoredEncryptedAndNeverReturned() {
		Map<String, Object> dto = service.create(new SourceForm("db", "DATABASE", true, null, null, null, 1, null, "POSTGRES", "db.local",
				5432, "logs", "reader", "s3cret!", null, "app_logs", "log_time", null, null, "message", null, null, null, null, 15));
		LogSource s = sources.findById(((Number) dto.get("id")).longValue()).orElseThrow();
		assertNotNull(s.getPasswordEnc());
		assertTrue(s.getPasswordEnc().startsWith("v1:") && !s.getPasswordEnc().contains("s3cret"));
		assertEquals(true, dto.get("hasPassword"));
		assertTrue(dto.keySet().stream().noneMatch(k -> k.toLowerCase().startsWith("password")));
		assertEquals("jdbc:postgresql://db.local:5432/logs", DatabasePoller.url(s));
	}

	@Test
	void sampleSiebelLogsAreWrittenAndRead() throws Exception {
		Map<String, Object> s = service.createSample();
		assertEquals(true, s.get("demo"));
		assertTrue(((Number) s.get("eventsIngested")).longValue() > 100, "three hours of back-filled Siebel activity");
		assertTrue(stored().stream().anyMatch(r -> "WfProcMgr".equals(r.getService())));
		assertTrue(stored().stream().allMatch(r -> r.getSourceFormat().equals("SIEBEL")));
	}
}
