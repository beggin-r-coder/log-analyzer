package com.anishka.log_analyzer.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.anishka.log_analyzer.dto.AiInsight;
import com.anishka.log_analyzer.entity.LogRecord;
import com.anishka.log_analyzer.repository.AiAnalysisRepository;
import com.anishka.log_analyzer.service.LogIngestionService;
import com.sun.net.httpserver.HttpServer;

/** Full flow against a fake Gemini endpoint: context, redaction, structured answer, audit row, cache. */
@SpringBootTest
class AiAnalysisServiceTest {

	static final List<String> PROMPTS = new CopyOnWriteArrayList<>();
	static final HttpServer SERVER;
	static {
		try {
			SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		SERVER.createContext("/", ex -> {
			PROMPTS.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			String text = "{\\\"summary\\\":\\\"NPCI timeouts are failing UPI payments\\\",\\\"rootCause\\\":\\\"NPCI latency\\\","
					+ "\\\"impact\\\":\\\"UPI payments fail\\\",\\\"severity\\\":\\\"high\\\",\\\"confidence\\\":82,"
					+ "\\\"recoverySteps\\\":[\\\"Check NPCI status\\\",\\\"Reconcile deemed transactions\\\"],"
					+ "\\\"preventiveActions\\\":[\\\"Alert on NPCI latency\\\"],\\\"evidence\\\":[\\\"Pattern #1: 3x\\\"],"
					+ "\\\"incidents\\\":[{\\\"title\\\":\\\"UPI timeouts\\\",\\\"pattern\\\":1,\\\"severity\\\":\\\"HIGH\\\",\\\"rootCause\\\":\\\"NPCI\\\",\\\"recovery\\\":\\\"Status check\\\"}]}";
			byte[] out = ("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + text + "\"}]},\"finishReason\":\"STOP\"}],"
					+ "\"usageMetadata\":{\"totalTokenCount\":321}}").getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, out.length);
			ex.getResponseBody().write(out);
			ex.close();
		});
		SERVER.start();
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("ai.gemini.enabled", () -> "true");
		r.add("ai.gemini.api-key", () -> "test-key");
		r.add("ai.gemini.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1beta");
	}

	@AfterAll
	static void stop() {
		SERVER.stop(0);
	}

	@Autowired
	AiAnalysisService ai;
	@Autowired
	LogIngestionService ingestion;
	@Autowired
	AiAnalysisRepository analyses;

	@Test
	void analysesASingleErrorAndAnOverview() {
		String ts = LocalDateTime.now().minusMinutes(2).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"));
		List<LogRecord> saved = ingestion.ingest(String.join("\n",
				ts + " [ERROR] [upi-switch] [req-aaaa1111] [49.36.10.2] NPCI switch timeout for NOVA00000000000001 after 30000ms (RespCode BT)",
				ts + " [ERROR] [upi-switch] [req-aaaa1111] [49.36.10.2] NPCI switch timeout for NOVA00000000000002 after 30000ms (RespCode BT)",
				ts + " [ERROR] [upi-switch] [req-bbbb2222] [10.1.1.1] NPCI switch timeout for NOVA00000000000003 after 30000ms (RespCode BT)",
				ts + " [INFO] [upi-switch] [req-aaaa1111] [49.36.10.2] UPI collect request received for priya@example.com card 4111111111111111"), null);
		long id = saved.get(0).getId();

		AiInsight one = ai.analyzeLog(id, false);
		assertEquals("GEMINI", one.source());
		assertEquals("HIGH", one.severity(), "severity is normalised");
		assertEquals(82, one.confidence());
		assertEquals(List.of("Check NPCI status", "Reconcile deemed transactions"), one.recoverySteps());
		assertEquals(3, ((Number) one.nlp().get("occurrences24h")).intValue(), "NLP counted the pattern");
		assertFalse(((List<?>) one.nlp().get("trace")).isEmpty(), "trace context found");
		assertTrue(one.redactions() >= 2, "email/card/IP redacted: " + one.redactionTypes());

		String prompt = PROMPTS.get(PROMPTS.size() - 1);
		assertFalse(prompt.contains("priya@example.com"), "email must not leave the app");
		assertFalse(prompt.contains("4111111111111111"), "card must not leave the app");
		assertFalse(prompt.contains("49.36.10.2"), "client IP must be masked");
		assertTrue(prompt.contains("NPCI switch timeout"));

		int calls = PROMPTS.size();
		AiInsight again = ai.analyzeLog(id, false);
		assertTrue(again.cached());
		assertEquals(calls, PROMPTS.size(), "cached analysis must not call Gemini again");
		assertNotNull(ai.latestForLog(id));

		AiInsight overview = ai.analyzeOverview("1h", true);
		assertEquals("GEMINI", overview.source());
		assertEquals(1, overview.incidents().size());
		assertTrue(((List<?>) overview.nlp().get("clusters")).size() >= 1);
		assertTrue(analyses.count() >= 2, "every analysis is stored for audit");
	}
}
