package com.anishka.log_analyzer.source;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/sources")
public class SourceController {

	private final LogSourceService service;

	public SourceController(LogSourceService service) {
		this.service = service;
	}

	@GetMapping
	public List<Map<String, Object>> list() {
		return service.list();
	}

	@GetMapping("/summary")
	public Map<String, Object> summary() {
		return service.summary();
	}

	@GetMapping("/{id}")
	public Map<String, Object> get(@PathVariable long id) {
		return service.get(id);
	}

	@PostMapping
	public Map<String, Object> create(@RequestBody SourceForm form) {
		return service.create(form);
	}

	@PutMapping("/{id}")
	public Map<String, Object> update(@PathVariable long id, @RequestBody SourceForm form) {
		return service.update(id, form);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/enabled")
	public Map<String, Object> enabled(@PathVariable long id, @RequestParam boolean value) {
		return service.setEnabled(id, value);
	}

	@PostMapping("/{id}/scan")
	public Map<String, Object> scan(@PathVariable long id) {
		return service.scan(id);
	}

	@PostMapping("/test")
	public Map<String, Object> test(@RequestBody SourceForm form, @RequestParam(required = false) Long id) {
		return service.test(form, id);
	}

	@PostMapping("/sample")
	public Map<String, Object> sample() throws IOException {
		return service.createSample();
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
		return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
	}
}
