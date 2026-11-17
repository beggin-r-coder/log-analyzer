package com.anishka.log_analyzer.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Apache Derby databases as log sources: embedded folders and network servers. */
@SpringBootTest
class DerbySourceTest {

	@Autowired LogSourceService service;
	@Autowired DatabasePoller poller;
	@Autowired LogSourceRepository sources;

	@Test
	void findsAndReadsALogTableInADerbyDatabase() throws Exception {
		String url = "jdbc:derby:memory:crmlogs" + System.nanoTime() + ";create=true";
		try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
			st.execute("CREATE TABLE CRM_CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(80))");
			st.execute("INSERT INTO CRM_CUSTOMER VALUES (1, 'Asha Rao'), (2, 'Vikram Shah')");
			st.execute("CREATE TABLE APP_EVENT_LOG (EVT_ID BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, LOGGED_AT TIMESTAMP, "
					+ "SEV VARCHAR(8), COMP VARCHAR(64), DETAIL VARCHAR(2000))");
			for (int i = 0; i < 25; i++) {
				st.execute("INSERT INTO APP_EVENT_LOG (LOGGED_AT, SEV, COMP, DETAIL) VALUES ({fn TIMESTAMPADD(SQL_TSI_SECOND, " + (i * 20 - 900)
						+ ", CURRENT_TIMESTAMP)}, '" + (i % 4 == 0 ? "ERROR" : "INFO") + "', 'EAIObjMgr_enu', '"
						+ (i % 4 == 0 ? "SBL-EAI-04308: Operation GetBalance failed: Read timed out" : "Outbound call completed in " + (100 + i) + " ms") + "')");
			}
		}
		SourceForm conn = new SourceForm("derby", "DATABASE", true, null, null, null, 1, null, null, null, null, null, null, null, url,
				null, null, null, null, null, null, null, null, null, 15);
		Map<String, Object> found = service.discover(conn, null);
		assertEquals(true, found.get("ok"), String.valueOf(found.get("error")));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> tables = (List<Map<String, Object>>) found.get("tables");
		assertEquals("APP_EVENT_LOG", tables.get(0).get("table"));

		Map<String, Object> saved = service.create(new SourceForm("derby", "DATABASE", true, null, null, null, 1, null, null, null, null, null,
				null, null, url, "APP_EVENT_LOG", null, null, null, null, null, null, null, null, 15));
		assertEquals("LOGGED_AT", saved.get("tsColumn"));
		assertEquals("DETAIL", saved.get("messageColumn"));
		assertEquals("EVT_ID", saved.get("idColumn"));
		assertEquals(25, poller.poll(sources.findById(((Number) saved.get("id")).longValue()).orElseThrow()));
	}

	@Test
	void derbyUrlsAndAdvice() {
		LogSource s = new LogSource();
		s.setDbType("DERBY");
		s.setDatabaseName("C:/apps/crm/logdb");
		assertEquals("jdbc:derby:C:/apps/crm/logdb", DatabasePoller.url(s));
		s.setHost("crm-db01");
		s.setDatabaseName("LOGDB");
		assertEquals("jdbc:derby://crm-db01:1527/LOGDB", DatabasePoller.url(s));
		assertTrue(ConnectionHints.explain(new SQLException("Failed to start database 'C:/apps/crm/logdb'",
				new SQLException("Another instance of Derby may have already booted the database C:\\apps\\crm\\logdb.")), s).contains("network server"));
	}
}
