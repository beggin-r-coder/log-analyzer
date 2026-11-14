package com.anishka.log_analyzer.dto;

import java.util.List;
import java.util.Map;

/**
 * Result of an AI (or offline NLP) analysis. Shown in the dashboard and the
 * single-error panel, and stored as JSON in the ai_analyses audit table.
 */
public record AiInsight(String scope, Long logId, String range, String source, String model, String summary,
		String rootCause, String impact, String severity, Integer confidence, List<String> recoverySteps,
		List<String> preventiveActions, List<String> evidence, List<Incident> incidents, String note,
		long generatedAt, int redactions, Map<String, Integer> redactionTypes, Long latencyMs,
		Map<String, Object> usage, Map<String, Object> nlp, boolean cached) {

	/** One incident (error pattern) inside an overview analysis. */
	public record Incident(String title, Integer pattern, String severity, String rootCause, String recovery) {
	}

	public AiInsight asCached() {
		return new AiInsight(scope, logId, range, source, model, summary, rootCause, impact, severity, confidence,
				recoverySteps, preventiveActions, evidence, incidents, note, generatedAt, redactions, redactionTypes,
				latencyMs, usage, nlp, true);
	}
}
