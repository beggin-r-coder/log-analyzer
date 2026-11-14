package com.anishka.log_analyzer.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.anishka.log_analyzer.simulator.BfsiLogGenerator;

class UniversalLogParserTest {

	private final UniversalLogParser parser = new UniversalLogParser();

	private ParsedLog parse(String line) {
		return parser.parseLine(line, null).orElseThrow();
	}

	@Test
	void originalBracketedFormat() {
		ParsedLog p = parse("2026-11-04T13:05:22.123 [ERROR] [payment-gateway] [req-ab12cd34] [10.2.3.4] Database connection lost");
		assertEquals(LogFormat.BRACKETED, p.getFormat());
		assertEquals("ERROR", p.getLevel());
		assertEquals("payment-gateway", p.getService());
		assertEquals("req-ab12cd34", p.getTraceId());
		assertEquals("10.2.3.4", p.getClientIp());
		assertEquals("Database connection lost", p.getMessage());
		assertEquals(LocalDateTime.of(2026, 11, 4, 13, 5, 22, 123_000_000), p.getTimestamp());
	}

	@Test
	void springBootFormatWithAppName() {
		ParsedLog p = parse("2026-11-04 13:05:22.123  WARN 4211 --- [core-banking] [nio-8080-exec-7] c.n.core.ledger.PostingService           : Retrying posting");
		assertEquals(LogFormat.SPRING_BOOT, p.getFormat());
		assertEquals("WARN", p.getLevel());
		assertEquals("core-banking", p.getService());
		assertEquals("nio-8080-exec-7", p.getThread());
		assertEquals("c.n.core.ledger.PostingService", p.getLogger());
		assertEquals("Retrying posting", p.getMessage());
	}

	@Test
	void springBootFormatWithoutAppName() {
		ParsedLog p = parse("2026-11-04 13:05:22.123 ERROR 4211 --- [           main] o.s.boot.SpringApplication : Application run failed");
		assertEquals(LogFormat.SPRING_BOOT, p.getFormat());
		assertEquals("main", p.getThread());
	}

	@Test
	void jsonIncludingNestedEcs() {
		ParsedLog p = parse("{\"@timestamp\":\"2026-11-04T07:35:22.123Z\",\"log\":{\"level\":\"error\"},\"service\":{\"name\":\"mobile-api\"},\"trace\":{\"id\":\"abc123def456\"},\"message\":\"JWT validation failed\"}");
		assertEquals(LogFormat.JSON, p.getFormat());
		assertEquals("ERROR", p.getLevel());
		assertEquals("mobile-api", p.getService());
		assertEquals("abc123def456", p.getTraceId());
		LocalDateTime expected = LocalDateTime.of(2026, 11, 4, 7, 35, 22, 123_000_000).atOffset(ZoneOffset.UTC)
				.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
		assertEquals(expected, p.getTimestamp());
	}

	@Test
	void log4jAndLogbackDefault() {
		ParsedLog a = parse("2026-11-04 13:05:22,123 ERROR [http-nio-8443-exec-3] com.bank.los.CreditBureauClient - CIBIL returned HTTP 500");
		assertEquals(LogFormat.LOG4J, a.getFormat());
		assertEquals("com.bank.los.CreditBureauClient", a.getLogger());
		ParsedLog b = parse("13:05:22.123 [main] INFO  com.bank.App - Started");
		assertEquals(LogFormat.LOG4J, b.getFormat());
		assertEquals("INFO", b.getLevel());
	}

	@Test
	void pythonLogging() {
		ParsedLog p = parse("2026-11-04 13:05:22,123 - kyc-service - WARNING - Aadhaar OTP delivery delayed");
		assertEquals(LogFormat.PYTHON, p.getFormat());
		assertEquals("WARN", p.getLevel());
		assertEquals("kyc-service", p.getService());
	}

	@Test
	void logfmtWithUtcTimestamp() {
		ParsedLog p = parse("ts=2026-11-04T07:35:22.123Z level=error service=card-auth trace_id=f00dbabe1234 msg=\"HSM connection refused\"");
		assertEquals(LogFormat.LOGFMT, p.getFormat());
		assertEquals("ERROR", p.getLevel());
		assertEquals("card-auth", p.getService());
		assertEquals("f00dbabe1234", p.getTraceId());
		assertEquals("HSM connection refused", p.getMessage());
	}

	@Test
	void nginxAccessLogDerivesLevelFromStatus() {
		ParsedLog p = parse("49.36.10.2 - - [04/Nov/2026:13:05:22 +0530] \"POST /api/v1/fund-transfer HTTP/1.1\" 504 157 \"-\" \"Mozilla/5.0\" rt=30.001");
		assertEquals(LogFormat.ACCESS_LOG, p.getFormat());
		assertEquals("ERROR", p.getLevel());
		assertEquals("49.36.10.2", p.getClientIp());
		assertTrue(p.getMessage().contains("HTTP 504"));
		assertEquals("WARN", parse("1.2.3.4 - - [04/Nov/2026:13:05:22 +0000] \"GET /x HTTP/1.1\" 404 0").getLevel());
	}

	@Test
	void syslogFormats() {
		ParsedLog a = parse("<131>1 2026-11-04T13:05:22.123+05:30 fraud-node-2 fraud-engine 3312 - - Fraud model inference failed");
		assertEquals(LogFormat.SYSLOG_RFC5424, a.getFormat());
		assertEquals("ERROR", a.getLevel());
		assertEquals("fraud-engine", a.getService());
		assertEquals("fraud-node-2", a.getHost());

		ParsedLog b = parse("Nov  4 13:05:22 atm-sw-01 atm-switch[2231]: WARN ATM00042 cash low: 7% remaining");
		assertEquals(LogFormat.SYSLOG_RFC3164, b.getFormat());
		assertEquals("WARN", b.getLevel());
		assertEquals("atm-switch", b.getService());
		assertEquals("ATM00042 cash low: 7% remaining", b.getMessage());
	}

	@Test
	void apacheErrorLog() {
		ParsedLog p = parse("[Wed Nov 04 13:05:22.123456 2026] [proxy:error] [pid 1234] [client 10.0.0.5:51234] AH00898: Error reading from remote server");
		assertEquals(LogFormat.APACHE_ERROR, p.getFormat());
		assertEquals("ERROR", p.getLevel());
		assertEquals("10.0.0.5", p.getClientIp());
	}

	@Test
	void pipeDelimited() {
		ParsedLog p = parse("2026/11/04 13:05:22.123 | ERROR | treasury-fx | SWIFT MT300 confirmation failed");
		assertEquals(LogFormat.PIPE_DELIMITED, p.getFormat());
		assertEquals("treasury-fx", p.getService());
	}

	@Test
	void genericFallbackNeverDropsALine() {
		ParsedLog a = parse("04-11-2026 13:05:22 ERR Insurer API returned 500");
		assertEquals(LogFormat.GENERIC, a.getFormat());
		assertEquals("ERROR", a.getLevel());
		assertEquals(LocalDateTime.of(2026, 11, 4, 13, 5, 22), a.getTimestamp());
		assertEquals("Insurer API returned 500", a.getMessage());

		ParsedLog b = parse("something completely unstructured failed badly");
		assertEquals("ERROR", b.getLevel());
		assertNotNull(b.getTimestamp());
	}

	@Test
	void stackTracesAreFoldedIntoTheirEvent() {
		String text = """
				2026-11-04 13:05:22,123 ERROR [main] com.bank.Ledger - Posting failed
				java.lang.IllegalStateException: boom
				\tat com.bank.Ledger.post(Ledger.java:42)
				\tat com.bank.Main.main(Main.java:7)
				Caused by: java.sql.SQLException: deadlock
				2026-11-04 13:05:23,000 INFO [main] com.bank.Ledger - Recovered
				""";
		List<ParsedLog> logs = parser.parse(text, null);
		// "java.lang.IllegalStateException: boom" has no timestamp, so it becomes its own generic event
		assertEquals("ERROR", logs.get(0).getLevel());
		assertEquals("INFO", logs.get(logs.size() - 1).getLevel());
		assertTrue(logs.stream().anyMatch(l -> l.getMessage().contains("Caused by: java.sql.SQLException")));
	}

	@Test
	void everySimulatedServiceRoundTripsThroughTheParser() {
		BfsiLogGenerator gen = new BfsiLogGenerator();
		Random rnd = new Random(42);
		LocalDateTime ts = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS).minusMinutes(5);
		Map<String, Integer> seen = new java.util.HashMap<>();
		for (int i = 0; i < 5000; i++) {
			BfsiLogGenerator.GeneratedLog g = gen.next(ts, rnd, i % 3 == 0 ? "core-banking" : null);
			List<ParsedLog> parsed = parser.parse(g.raw(), g.service());
			assertEquals(1, parsed.size(), () -> "one event expected for: " + g.raw());
			ParsedLog p = parsed.get(0);
			assertEquals(g.service(), p.getService(), () -> "service for: " + g.raw());
			// access logs have second precision, everything else is exact
			assertTrue(Math.abs(ChronoUnit.SECONDS.between(ts, p.getTimestamp())) <= 1, () -> "ts for: " + g.raw());
			if (!g.service().equals("insurance-claims")) {
				assertNotEquals(LogFormat.GENERIC, p.getFormat(), () -> "format for: " + g.raw());
			}
			seen.merge(p.getFormat().name(), 1, Integer::sum);
		}
		assertTrue(seen.size() >= 10, "formats seen: " + seen);
	}
}
