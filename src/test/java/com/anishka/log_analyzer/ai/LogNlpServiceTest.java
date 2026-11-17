package com.anishka.log_analyzer.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.anishka.log_analyzer.entity.LogRecord;

class LogNlpServiceTest {

	private final LogNlpService nlp = new LogNlpService();
	private long ids = 1;

	private LogRecord rec(String service, String msg) {
		LogRecord r = new LogRecord();
		ReflectionTestUtils.setField(r, "id", ids++);
		r.setService(service);
		r.setMessage(msg);
		r.setLevel("ERROR");
		r.setLevelRank(4);
		r.setTimestamp(LocalDateTime.now().minusMinutes(ids));
		return r;
	}

	@Test
	void templatesMaskVariablePartsButKeepTheShape() {
		String a = nlp.template("NPCI switch timeout for NOVA03923614376119 after 30000ms (RespCode BT)");
		String b = nlp.template("NPCI switch timeout for NOVA48723685508048 after 12000ms (RespCode BT)");
		assertEquals(a, b);
		assertEquals("NPCI switch timeout for <ID> after <NUM>ms (RespCode BT)", a);
		assertEquals("Fund transfer <ID> posted: debit acct <MASKED> amount <AMOUNT>",
				nlp.template("Fund transfer TXN123456789012 posted: debit acct XXXXXX1234 amount INR 5,000.00"));
		assertEquals("Login from <IP> failed for <EMAIL>", nlp.template("Login from 10.20.3.4 failed for a.b@x.com"));
		// meaningful codes survive masking
		assertEquals("Push notification vendor returned HTTP 503", nlp.template("Push notification vendor returned HTTP 503"));
		assertEquals("ISO8583 <NUM> response timeout from switch for <ID>", nlp.template("ISO8583 0210 response timeout from switch for ATM97200"));
	}

	@Test
	void clustersCollapseRepeatedErrors() {
		List<LogRecord> recs = new ArrayList<>();
		for (int i = 0; i < 5; i++) recs.add(rec("upi-switch", "NPCI switch timeout for NOVA0000000000000" + i + " after 30000ms"));
		for (int i = 0; i < 2; i++) recs.add(rec("core-banking", "HikariPool-1 - Connection is not available, request timed out after 30000ms"));
		List<LogNlpService.Cluster> c = nlp.clusters(recs, 10);
		assertEquals(2, c.size());
		assertEquals(5, c.get(0).count());
		assertEquals(1, c.get(0).rank());
		assertTrue(c.get(0).services().contains("upi-switch"));
	}

	@Test
	void extractsBankingEntities() {
		Map<String, List<String>> e = nlp.entities(
				"java.sql.SQLRecoverableException: ORA-17002 on ACCT_BALANCE for TXN123456789012 acct XXXXXX4821 after 30000ms, HTTP 503");
		assertEquals(List.of("SQLRecoverableException"), e.get("exception"));
		assertTrue(e.get("errorCode").contains("HTTP 503"));
		assertEquals(List.of("TXN123456789012"), e.get("transaction"));
		assertEquals(List.of("XXXXXX4821"), e.get("account"));
		assertEquals(List.of("ACCT_BALANCE"), e.get("table"));
	}

	@Test
	void similarityRanksRelatedErrorsFirst() {
		LogRecord target = rec("upi-switch", "NPCI switch timeout for NOVA111 after 30000ms (RespCode BT)");
		List<LogRecord> candidates = List.of(
				rec("upi-switch", "NPCI switch timeout for NOVA222 after 25000ms (RespCode BT)"),
				rec("upi-switch", "UPI transaction NOVA333 deemed failed: no response from NPCI"),
				rec("card-auth", "CVV mismatch for card, attempt 2/3"));
		List<LogNlpService.Similar> sim = nlp.similar(target, candidates, 5, 0.1);
		assertTrue(sim.get(0).score() > 0.9, "same pattern should be near-identical");
		assertTrue(sim.stream().noneMatch(s -> s.record().getService().equals("card-auth")), "unrelated error excluded");
	}

	@Test
	void keywordsComeFromTheErrorsNotFromIds() {
		List<String> kw = nlp.keywords(List.of("NPCI switch timeout for NOVA1 after 30000ms",
				"NPCI switch timeout for NOVA2 after 30000ms", "HSM connection refused"), 5);
		assertTrue(kw.contains("npci") && kw.contains("timeout"));
		assertTrue(kw.stream().noneMatch(k -> k.startsWith("nova")));
	}
}
