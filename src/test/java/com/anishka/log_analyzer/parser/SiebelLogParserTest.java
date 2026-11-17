package com.anishka.log_analyzer.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.anishka.log_analyzer.ai.LogNlpService;
import com.anishka.log_analyzer.ai.RecoveryPlaybook;
import com.anishka.log_analyzer.service.ErrorClassifier;

/** Oracle Siebel CRM component / server logs: header, tab-separated events, severities and multi-line SQL. */
class SiebelLogParserTest {

	private final UniversalLogParser parser = new UniversalLogParser();

	static final String COMPONENT_LOG = String.join("\n",
			"2021 2026-11-12 10:15:01 0000-00-00 00:00:00 +0530 00000000 001 003f 0001 09 SCCObjMgr_enu 2097155 5360 4940 d:\\siebel\\log\\SCCObjMgr_enu_0020_2097155.log 8.1.1.11 [23030] ENU",
			"ObjMgrSessionInfo\tObjMgrLogin\t3\t0000000257c41c8c:0\t2026-11-12 10:15:02\tLogin name : AGENT0421",
			"ServerLog\tProcessCreate\t1\t0000000257c41c8c:0\t2026-11-12 10:15:02\tCreated multithreaded server process (OS pid = 5360) for SCCObjMgr_enu",
			"SQLParseAndExecute\tStatement\t4\t0000000257c41c8c:0\t2026-11-12 10:15:03\tSELECT statement with ID: 1C2C3D30",
			"  SELECT /*+ ALL_ROWS */",
			"      T1.ROW_ID, T1.SR_NUM",
			"  FROM SIEBEL.S_SRV_REQ T1",
			"GenericLog\tGenericWarning\t2\t0000000257c41c8c:0\t2026-11-12 10:15:04\tSQL statement took 12.4 seconds for BusComp 'Service Request'",
			"GenericLog\tGenericError\t1\t000000025a1c1a24:0\t2026-11-12 10:15:05\t(sstchca.cpp (2096) err=7668017 sys=0) SBL-DAT-00382: The same values for 'Account Name' already exist.",
			"SQLError\tError\t1\t000000025a1c1a24:0\t2026-11-12 10:15:06\tSBL-DBC-00105: An error has occurred executing a Sql statement.",
			"[DataDirect][ODBC Oracle Wire Protocol driver][Oracle]ORA-12170: TNS:Connect timeout occurred",
			"ObjMgrBusServiceLog\tInvokeMethod\t4\t000000025a1c1a24:0\t2026-11-12 10:15:07\tBegin: Business Service 'NB Customer 360' invoke method: 'GetProfile'",
			"ServerLog\tProcessExit\t1\t0\t2026-11-12 10:15:09\tSCCObjMgr_enu  5360  TERMINATED  Process 5360 was terminated");

	private static LocalDateTime ist(int h, int m, int s) {
		return LocalDateTime.of(2026, 11, 12, h, m, s).atOffset(ZoneOffset.ofHoursMinutes(5, 30))
				.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
	}

	@Test
	void componentLogIsParsedEventByEvent() {
		List<ParsedLog> logs = parser.parse(COMPONENT_LOG, null);
		assertEquals(9, logs.size(), "header + 8 events; SQL and ODBC continuation lines are folded");
		logs.forEach(p -> {
			assertEquals(LogFormat.SIEBEL, p.getFormat());
			assertEquals("SCCObjMgr_enu", p.getService(), "component name comes from the file header");
			assertEquals("task 2097155", p.getThread());
		});

		ParsedLog header = logs.get(0);
		assertEquals("INFO", header.getLevel());
		assertTrue(header.getMessage().contains("Siebel 8.1.1.11 [23030] ENU"));
		assertEquals(ist(10, 15, 1), header.getTimestamp(), "times are converted from the header's +0530 offset");

		ParsedLog login = logs.get(1);
		assertEquals("INFO", login.getLevel());
		assertEquals("ObjMgrSessionInfo.ObjMgrLogin", login.getLogger());
		assertEquals("0000000257c41c8c:0", login.getTraceId(), "the SARM id links events of one request");
		assertEquals("Login name : AGENT0421", login.getMessage());
		assertEquals(ist(10, 15, 2), login.getTimestamp());

		assertEquals("INFO", logs.get(2).getLevel(), "ProcessCreate is logged at severity 1 but is routine");

		ParsedLog sql = logs.get(3);
		assertEquals("DEBUG", sql.getLevel());
		assertTrue(sql.getMessage().contains("FROM SIEBEL.S_SRV_REQ T1"), "multi-line SQL stays in its event");

		assertEquals("WARN", logs.get(4).getLevel());
		assertEquals("ERROR", logs.get(5).getLevel());
		ParsedLog dbc = logs.get(6);
		assertEquals("ERROR", dbc.getLevel());
		assertTrue(dbc.getMessage().contains("ORA-12170"), "driver error line is folded into the SQLError event");
		assertEquals("DEBUG", logs.get(7).getLevel());
		ParsedLog exit = logs.get(8);
		assertEquals("FATAL", exit.getLevel());
		assertNull(exit.getTraceId(), "a SARM id of 0 means none");
	}

	@Test
	void eventLinesWithoutHeaderAndWithSpacesInsteadOfTabs() {
		ParsedLog p = parser.parseLine("EAIObjMgr  WebServiceError  1  00000a9f5c2f1e88:0  2026-11-12 11:00:00  "
				+ "SBL-EAI-04308: Operation 'CreateCustomer' of Web Service 'NB CBS Integration' failed: Connection timed out", "crm").orElseThrow();
		assertEquals(LogFormat.SIEBEL, p.getFormat());
		assertEquals("ERROR", p.getLevel());
		assertEquals("crm", p.getService());
		assertEquals(LocalDateTime.of(2026, 11, 12, 11, 0, 0), p.getTimestamp(), "no header: taken as local time");
	}

	@Test
	void headerWithSpacesInThePathAndNoVersion() {
		List<ParsedLog> logs = parser.parse(String.join("\n",
				"2021 2026-11-12 10:15:01 0000-00-00 00:00:00 +0000 00000000 001 003f 0001 09 EAIObjMgr_enu 4411 2210 8812 D:\\Program Files\\Siebel\\log\\EAIObjMgr_enu_0007_4411.log",
				"GenericLog\tGenericError\t1\t0000000257c41c8c:0\t2026-11-12 10:15:05\tSBL-EAI-04308: failed"), null);
		assertEquals(2, logs.size());
		assertEquals("EAIObjMgr_enu", logs.get(1).getService());
		assertTrue(logs.get(0).getMessage().endsWith("EAIObjMgr_enu_0007_4411.log"), logs.get(0).getMessage());
	}

	@Test
	void severityMapping() {
		assertEquals("INFO", UniversalLogParser.siebelLevel("ServerLog", "LstnObjCreate", 1, "Created port 49170 for Server Request Processor"));
		assertEquals("ERROR", UniversalLogParser.siebelLevel("ServerLog", "ProcessExit", 1, "SCCObjMgr_enu 4711 EXITED Process exited with error - code 1"));
		assertEquals("FATAL", UniversalLogParser.siebelLevel("GenericLog", "GenericError", 1, "Process crashed with core dump"));
		assertEquals("ERROR", UniversalLogParser.siebelLevel("SQLError", "Error", 4, "anything"));
		assertEquals("WARN", UniversalLogParser.siebelLevel("GenericLog", "GenericWarning", 2, "slow"));
		assertEquals("DEBUG", UniversalLogParser.siebelLevel("ObjMgrBusServiceLog", "InvokeMethod", 4, "Begin: Business Service 'Error Handler'"));
		assertEquals("TRACE", UniversalLogParser.siebelLevel("EventContext", "EvtCtxApplet", 5, "Applet: SR List Applet"));
	}

	@Test
	void classificationNlpAndPlaybookUnderstandSiebel() {
		ErrorClassifier c = new ErrorClassifier();
		assertEquals("Siebel Database Connector", c.classify("SBL-DBC-00105: An error has occurred executing a Sql statement."));
		assertEquals("Siebel EAI & Integration", c.classify("SBL-EAI-04308: Operation 'X' of Web Service 'Y' failed"));
		assertEquals("Siebel Workflow", c.classify("SBL-BPR-00162: Error invoking service 'NB Lead Assignment'"));
		assertEquals("Siebel Data Validation", c.classify("SBL-DAT-00382: The same values for 'Account Name' already exist."));
		assertEquals("Siebel Server & Components", c.classify("SCCObjMgr_enu 5360 TERMINATED Process 5360 was terminated"));

		LogNlpService nlp = new LogNlpService();
		String t = nlp.template("(sstchca.cpp (2096) err=7668017 sys=0) SBL-DAT-00382: duplicate for row 1-ABC123");
		assertTrue(t.contains("SBL-DAT-00382"), "the Siebel error code survives masking: " + t);
		assertTrue(t.contains("<ROWID>"), "Siebel row ids are masked: " + t);
		assertTrue(nlp.entities("SBL-EAI-04308: Web Service 'NB CBS Integration' failed for 1-2Z3X9").get("errorCode").contains("SBL-EAI-04308"));

		RecoveryPlaybook book = new RecoveryPlaybook();
		assertEquals("siebel-dbc", book.match("SBL-DBC-00105 ORA-12170 timeout").id());
		assertEquals("siebel-eai", book.match("SBL-EAI-04308 Web Service failed").id());
		assertEquals("siebel-server", book.match("Process 5360 was terminated").id());
	}
}
