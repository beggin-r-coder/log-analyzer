package com.anishka.log_analyzer.category;

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
@RequestMapping("/api/categories")
public class CategoryController {

	private final CategoryService service;

	public CategoryController(CategoryService service) {
		this.service = service;
	}

	@GetMapping
	public List<Map<String, Object>> list() {
		return service.list();
	}

	@GetMapping("/stats")
	public Map<String, Object> stats(@RequestParam(defaultValue = "24h") String range, @RequestParam(required = false) Integer tz) {
		return service.stats(range, tz, null);
	}

	@PostMapping("/preview")
	public Map<String, Object> preview(@RequestBody CategoryForm form, @RequestParam(defaultValue = "24h") String range,
			@RequestParam(required = false) Integer tz) {
		return service.preview(form, range, tz);
	}

	@PostMapping
	public Map<String, Object> create(@RequestBody CategoryForm form) {
		return service.create(form);
	}

	@PutMapping("/{id}")
	public Map<String, Object> update(@PathVariable long id, @RequestBody CategoryForm form) {
		return service.update(id, form);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
		return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
	}
}
