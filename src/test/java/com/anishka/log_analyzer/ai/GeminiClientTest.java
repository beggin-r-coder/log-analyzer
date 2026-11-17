package com.anishka.log_analyzer.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Runs the client against a fake Gemini endpoint on localhost. */
class GeminiClientTest {

	private HttpServer server;
	private final List<String> paths = new ArrayList<>();
	private final List<String> keys = new ArrayList<>();
	private final List<String> bodies = new ArrayList<>();

	private GeminiClient start(String key, int[] statuses, String... responses) throws IOException {
		AtomicInteger n = new AtomicInteger();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", (HttpExchange ex) -> {
			int i = Math.min(n.getAndIncrement(), responses.length - 1);
			paths.add(ex.getRequestURI().getPath());
			keys.add(ex.getRequestHeaders().getFirst("x-goog-api-key"));
			bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] out = responses[i].getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "application/json");
			ex.sendResponseHeaders(statuses[Math.min(i, statuses.length - 1)], out.length);
			ex.getResponseBody().write(out);
			ex.close();
		});
		server.start();
		return new GeminiClient(key, "gemini-test-model", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta", 10);
	}

	@AfterEach
	void stop() {
		if (server != null) server.stop(0);
	}

	private static String answer(String text) {
		return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"thinking...\",\"thought\":true},{\"text\":"
				+ quote(text) + "}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":120,\"candidatesTokenCount\":80,\"totalTokenCount\":200}}";
	}

	private static String quote(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}

	@Test
	void sendsKeyHeaderAndStructuredOutputAndParsesTheAnswer() throws IOException {
		GeminiClient c = start("test-key", new int[] { 200 }, answer("{\"summary\":\"UPI timeouts\",\"confidence\":80}"));
		GeminiClient.Reply r = c.generateJson("system rules", "user prompt", Map.of("type", "OBJECT"));
		assertEquals("UPI timeouts", r.json().get("summary"));
		assertEquals(80, ((Number) r.json().get("confidence")).intValue());
		assertEquals("/v1beta/models/gemini-test-model:generateContent", paths.get(0));
		assertEquals("test-key", keys.get(0));
		String body = bodies.get(0);
		assertTrue(body.contains("\"systemInstruction\"") && body.contains("system rules"));
		assertTrue(body.contains("\"responseMimeType\":\"application/json\""));
		assertTrue(body.contains("\"responseSchema\""));
		assertFalse(body.contains("temperature"), "sampling params are deprecated on newer models");
		assertEquals(200, ((Number) r.usage().get("totalTokenCount")).intValue());
	}

	@Test
	void acceptsJsonWrappedInAFence() throws IOException {
		GeminiClient c = start("k", new int[] { 200 }, answer("Here you go:\n```json\n{\"summary\":\"ok\"}\n```"));
		assertEquals("ok", c.generateJson("s", "u", null).json().get("summary"));
	}

	@Test
	void retriesWithoutSchemaWhenTheSchemaIsRejected() throws IOException {
		GeminiClient c = start("k", new int[] { 400, 200 },
				"{\"error\":{\"code\":400,\"message\":\"Invalid JSON payload: unknown field responseSchema\"}}",
				answer("{\"summary\":\"fallback worked\"}"));
		assertEquals("fallback worked", c.generateJson("s", "u", Map.of("type", "OBJECT")).json().get("summary"));
		assertEquals(2, bodies.size());
		assertFalse(bodies.get(1).contains("responseSchema"));
	}

	@Test
	void explainsCommonFailures() throws IOException {
		GeminiClient c = start("bad", new int[] { 404 }, "{\"error\":{\"code\":404,\"message\":\"models/x is not found\"}}");
		AiException e = assertThrows(AiException.class, () -> c.generateJson("s", "u", null));
		assertEquals(404, e.getStatus());
		assertTrue(e.getMessage().contains("GEMINI_MODEL"), e.getMessage());
		assertFalse(e.getMessage().contains("bad"), "never echo the key");
	}

	@Test
	void notConfiguredWithoutKey() {
		GeminiClient c = new GeminiClient("", "m", "http://127.0.0.1:1", 5);
		assertFalse(c.isConfigured());
		assertThrows(AiException.class, () -> c.generateJson("s", "u", null));
	}
}
