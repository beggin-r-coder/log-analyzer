package com.anishka.log_analyzer.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal client for the Gemini API (Google AI Studio) generateContent
 * endpoint. The API key comes from configuration (GEMINI_API_KEY) and is never
 * logged or returned to the browser.
 */
@Component
public class GeminiClient {

	private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
	private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

	/** Result of one call: the parsed JSON object plus metadata for display and audit. */
	public record Reply(Map<String, Object> json, String model, long latencyMs, Map<String, Object> usage) {
	}

	private final String apiKey;
	private final String model;
	private final String baseUrl;
	private final Duration timeout;
	private final JsonMapper json = JsonMapper.builder().build();
	private final HttpClient http;

	public GeminiClient(@Value("${ai.gemini.api-key:}") String apiKey,
			@Value("${ai.gemini.model:gemini-3.8-flash}") String model,
			@Value("${ai.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
			@Value("${ai.gemini.timeout-seconds:60}") int timeoutSeconds) {
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.model = model.trim();
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.timeout = Duration.ofSeconds(timeoutSeconds);
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
	}

	public boolean isConfigured() {
		return !apiKey.isEmpty();
	}

	public String getModel() {
		return model;
	}

	/**
	 * Asks the model for a JSON object. The schema is enforced via structured
	 * output; if the API rejects the schema, the call is retried once asking
	 * for plain JSON, and the answer is still parsed.
	 */
	public Reply generateJson(String systemPrompt, String userPrompt, Map<String, Object> schema) {
		if (!isConfigured()) {
			throw new AiException("Gemini is not configured. Set the GEMINI_API_KEY environment variable.", 0);
		}
		try {
			return call(systemPrompt, userPrompt, schema);
		} catch (AiException e) {
			if (e.getStatus() == 400 && schema != null && e.getMessage().toLowerCase().contains("schema")) {
				log.warn("Gemini rejected the response schema, retrying without it: {}", e.getMessage());
				return call(systemPrompt, userPrompt + "\n\nRespond with a single JSON object only.", null);
			}
			if (e.getStatus() == 429 || e.getStatus() == 503) {
				sleep(2500); // one polite retry for rate limits / overload
				return call(systemPrompt, userPrompt, schema);
			}
			throw e;
		}
	}

	private Reply call(String systemPrompt, String userPrompt, Map<String, Object> schema) {
		Map<String, Object> generationConfig = new LinkedHashMap<>();
		generationConfig.put("responseMimeType", "application/json");
		if (schema != null) {
			generationConfig.put("responseSchema", schema);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
		body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))));
		body.put("generationConfig", generationConfig);

		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/models/" + model + ":generateContent"))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.header("x-goog-api-key", apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
				.build();

		long started = System.currentTimeMillis();
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new AiException("Could not reach the Gemini API: " + e.getMessage(), 0, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AiException("Gemini request was interrupted", 0, e);
		}
		long latency = System.currentTimeMillis() - started;
		if (response.statusCode() / 100 != 2) {
			throw new AiException(describeError(response.statusCode(), response.body()), response.statusCode());
		}
		return new Reply(parseAnswer(response.body()), model, latency, usage(response.body()));
	}

	@SuppressWarnings("unchecked")
	Map<String, Object> parseAnswer(String responseBody) {
		Map<String, Object> root = json.readValue(responseBody, Map.class);
		Object feedback = root.get("promptFeedback");
		if (feedback instanceof Map<?, ?> fb && fb.get("blockReason") != null) {
			throw new AiException("Gemini blocked the request (" + fb.get("blockReason") + ")", 200);
		}
		List<Map<String, Object>> candidates = (List<Map<String, Object>>) root.get("candidates");
		if (candidates == null || candidates.isEmpty()) {
			throw new AiException("Gemini returned no answer", 200);
		}
		Map<String, Object> candidate = candidates.get(0);
		Map<String, Object> content = (Map<String, Object>) candidate.get("content");
		StringBuilder text = new StringBuilder();
		if (content != null && content.get("parts") instanceof List<?> parts) {
			for (Object p : parts) {
				if (p instanceof Map<?, ?> part && !Boolean.TRUE.equals(part.get("thought")) && part.get("text") != null) {
					text.append(part.get("text"));
				}
			}
		}
		if (text.isEmpty()) {
			throw new AiException("Gemini returned an empty answer (finish reason: " + candidate.get("finishReason") + ")", 200);
		}
		return toJsonObject(text.toString());
	}

	/** Accepts a bare JSON object, or one wrapped in prose or a ```json fence. */
	@SuppressWarnings("unchecked")
	Map<String, Object> toJsonObject(String text) {
		String t = text.trim();
		try {
			return json.readValue(t, Map.class);
		} catch (RuntimeException ignored) {
			Matcher m = JSON_BLOCK.matcher(t);
			if (m.find()) {
				try {
					return json.readValue(m.group(), Map.class);
				} catch (RuntimeException ignoredAgain) {
					// fall through
				}
			}
		}
		// last resort: keep the prose so the user still sees the answer
		Map<String, Object> fallback = new LinkedHashMap<>();
		fallback.put("summary", t.length() > 4000 ? t.substring(0, 4000) : t);
		return fallback;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> usage(String body) {
		try {
			Object u = json.readValue(body, Map.class).get("usageMetadata");
			return u instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
		} catch (RuntimeException e) {
			return Map.of();
		}
	}

	@SuppressWarnings("unchecked")
	private String describeError(int status, String body) {
		String apiMessage = "";
		try {
			Object err = json.readValue(body, Map.class).get("error");
			if (err instanceof Map<?, ?> e && e.get("message") != null) {
				apiMessage = String.valueOf(e.get("message"));
			}
		} catch (RuntimeException ignored) {
			apiMessage = body == null ? "" : body.substring(0, Math.min(200, body.length()));
		}
		String hint = switch (status) {
		case 400 -> apiMessage.toLowerCase().contains("api key") ? "The Gemini API key is invalid." : "Gemini rejected the request.";
		case 401, 403 -> "The Gemini API key is not allowed to use this model.";
		case 404 -> "Model '" + model + "' was not found. Set GEMINI_MODEL to a current model, e.g. gemini-3.8-flash or gemini-3.5-flash-lite.";
		case 429 -> "Gemini rate limit or quota reached. Wait a minute and try again.";
		default -> status >= 500 ? "Gemini is temporarily unavailable." : "Gemini request failed.";
		};
		return hint + (apiMessage.isEmpty() ? "" : " (" + apiMessage + ")") + " [HTTP " + status + "]";
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
