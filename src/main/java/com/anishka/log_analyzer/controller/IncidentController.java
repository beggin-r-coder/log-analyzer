package com.anishka.log_analyzer.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.anishka.log_analyzer.entity.AlertRule;
import com.anishka.log_analyzer.incident.ImpactService;
import com.anishka.log_analyzer.incident.IncidentService;

@RestController
@RequestMapping("/api")
public class IncidentController {

	private final IncidentService incidents;
	private final ImpactService impact;

	public IncidentController(IncidentService incidents, ImpactService impact) {
		this.incidents = incidents;
		this.impact = impact;
	}

	@GetMapping("/impact")
	public Map<String, Object> impact(@RequestParam(defaultValue = "24h") String range) {
		return impact.summary(range);
	}

	@GetMapping("/incidents")
	public Map<String, Object> list(@RequestParam(required = false) String status,
			@RequestParam(defaultValue = "7d") String range) {
		return incidents.list(status, range);
	}

	/** Runs the alert rules now instead of waiting for the next scheduled pass. */
	@PostMapping("/incidents/evaluate")
	public Map<String, Object> evaluate(@RequestParam(defaultValue = "false") boolean force) {
		incidents.evaluate(java.time.LocalDateTime.now(), force);
		return incidents.list("active", "24h");
	}

	@GetMapping("/incidents/{id}")
	public Map<String, Object> detail(@PathVariable long id) {
		return incidents.detail(id, impact);
	}

	@PostMapping("/incidents/{id}/ack")
	public Map<String, Object> ack(@PathVariable long id, Authentication auth) {
		incidents.acknowledge(id, user(auth));
		return incidents.detail(id, impact);
	}

	@PostMapping("/incidents/{id}/resolve")
	public Map<String, Object> resolve(@PathVariable long id, @RequestBody(required = false) Map<String, String> body,
			Authentication auth) {
		incidents.resolve(id, user(auth), body == null ? null : body.get("note"));
		return incidents.detail(id, impact);
	}

	@PostMapping("/incidents/{id}/notes")
	public Map<String, Object> note(@PathVariable long id, @RequestBody Map<String, String> body, Authentication auth) {
		incidents.note(id, user(auth), body.get("text"));
		return incidents.detail(id, impact);
	}

	@GetMapping("/alert-rules")
	public List<AlertRule> rules() {
		return incidents.rules();
	}

	@PostMapping("/alert-rules")
	public AlertRule create(@RequestBody AlertRule rule) {
		return incidents.saveRule(null, rule);
	}

	@PutMapping("/alert-rules/{id}")
	public AlertRule update(@PathVariable long id, @RequestBody AlertRule rule) {
		return incidents.saveRule(id, rule);
	}

	@DeleteMapping("/alert-rules/{id}")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		incidents.deleteRule(id);
		return ResponseEntity.noContent().build();
	}

	@ExceptionHandler(IncidentService.NotFound.class)
	public ResponseEntity<Map<String, String>> notFound(IncidentService.NotFound e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
		return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
	}

	private static String user(Authentication auth) {
		return auth == null ? "anonymous" : auth.getName();
	}
}
