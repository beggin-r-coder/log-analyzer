package com.anishka.log_analyzer.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.anishka.log_analyzer.ai.AiAnalysisService;
import com.anishka.log_analyzer.dto.AiInsight;

/** AI error analysis: whole-range overview and single-log analysis, plus NLP context. */
@RestController
@RequestMapping("/api/ai")
public class AiController {

	private final AiAnalysisService ai;

	public AiController(AiAnalysisService ai) {
		this.ai = ai;
	}

	@GetMapping("/status")
	public Map<String, Object> status() {
		return ai.status();
	}

	@PostMapping("/overview")
	public AiInsight analyzeOverview(@RequestParam(defaultValue = "24h") String range,
			@RequestParam(defaultValue = "false") boolean force) {
		return ai.analyzeOverview(range, force);
	}

	/** The last stored overview for a range, without calling the AI (204 if none). */
	@GetMapping("/overview/latest")
	public ResponseEntity<AiInsight> latestOverview(@RequestParam(defaultValue = "24h") String range) {
		AiInsight a = ai.latestOverview(range);
		return a == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(a);
	}

	/** NLP-only context (pattern, frequency, entities, similar errors, trace); instant and free. */
	@GetMapping("/logs/{id}/context")
	public Map<String, Object> context(@PathVariable long id) {
		return ai.logContext(id);
	}

	@PostMapping("/logs/{id}")
	public AiInsight analyzeLog(@PathVariable long id, @RequestParam(defaultValue = "false") boolean force) {
		return ai.analyzeLog(id, force);
	}

	@GetMapping("/logs/{id}/latest")
	public ResponseEntity<AiInsight> latestForLog(@PathVariable long id) {
		AiInsight a = ai.latestForLog(id);
		return a == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(a);
	}
}
