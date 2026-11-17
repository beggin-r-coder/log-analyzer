package com.anishka.log_analyzer.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.anishka.log_analyzer.category.CategoryMatcher.Rule;
import com.anishka.log_analyzer.dto.LogQuery;
import com.anishka.log_analyzer.parser.LogFormat;
import com.anishka.log_analyzer.parser.ParsedLog;
import com.anishka.log_analyzer.repository.IncidentEventRepository;
import com.anishka.log_analyzer.repository.IncidentRepository;
import com.anishka.log_analyzer.repository.LogRecordRepository;
import com.anishka.log_analyzer.service.LogIngestionService;
import com.anishka.log_analyzer.service.LogQueryService;

/** User-defined categories: matching rules, counts over time, the explorer filter and spike alerts. */
@SpringBootTest
class CustomCategoryTest {

	@Autowired CategoryService service;
	@Autowired CustomCategoryRepository repo;
	@Autowired LogIngestionService ingestion;
	@Autowired LogRecordRepository logs;
	@Autowired LogQueryService query;
	@Autowired IncidentRepository incidents;
	@Autowired IncidentEventRepository events;

	@BeforeEach
	void data() {
		events.deleteAll();
		incidents.deleteAll();
		repo.deleteAll();
		logs.deleteAll();
		LocalDateTime now = LocalDateTime.now();
		List<ParsedLog> batch = new ArrayList<>();
		for (int i = 0; i < 12; i++) batch.add(log(now.minusMinutes(i * 5), "ERROR", "SCCObjMgr_enu", "SBL-DAT-00398: Record not found for Account " + i));
		for (int i = 0; i < 4; i++) batch.add(log(now.minusMinutes(i * 7), "WARN", "EAIObjMgr_enu", "record NOT FOUND in cache, reloading"));
		for (int i = 0; i < 5; i++) batch.add(log(now.minusMinutes(i * 3), "ERROR", "EAIObjMgr_enu", "SBL-EAI-04308: Operation GetBalance failed: Read timed out"));
		for (int i = 0; i < 6; i++) batch.add(log(now.minusMinutes(i), "INFO", "WfProcMgr", "Workflow step completed"));
		batch.add(log(now.minusHours(30), "ERROR", "SCCObjMgr_enu", "SBL-DAT-00398: Record not found (yesterday)"));
		ingestion.save(batch);
	}

	private static ParsedLog log(LocalDateTime t, String level, String svc, String msg) {
		return new ParsedLog().timestamp(t).level(level).service(svc).message(msg).format(LogFormat.SIEBEL).rawLine(msg);
	}

	private static CategoryForm form(String name, boolean all, List<String> levels, Rule... rules) {
		return new CategoryForm(name, null, null, true, all, levels, List.of(rules), true, "LINE", false, null, null, null);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> only(Map<String, Object> stats) {
		return ((List<Map<String, Object>>) stats.get("categories")).get(0);
	}

	@Test
	void countsEventsContainingAPhraseOverTime() {
		service.create(form("Record not found", false, List.of(), new Rule("message", "contains", "Record not found", null)));
		Map<String, Object> c = only(service.stats("24h", null, null));
		assertEquals(16L, c.get("count"), "case-insensitive by default, any level");
		assertEquals(1L, c.get("previous"), "the day before");
		assertEquals(12L, c.get("errors"));
		@SuppressWarnings("unchecked")
		List<Long> series = (List<Long>) c.get("series");
		assertEquals(16L, series.stream().mapToLong(Long::longValue).sum());
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> top = (List<Map<String, Object>>) c.get("topServices");
		assertEquals("SCCObjMgr_enu", top.get(0).get("name"));
	}

	@Test
	void levelsCaseAllAnyWildcardsAndPatterns() {
		Map<String, Object> exact = service.preview(form("x", false, List.of(), new Rule("message", "contains", "Record not found", true)), "24h", null);
		assertEquals(12L, only(exact).get("count"), "case-sensitive");
		Map<String, Object> errorsOnly = service.preview(form("x", false, List.of("ERROR"), new Rule("message", "contains", "record not found", null)), "24h", null);
		assertEquals(12L, only(errorsOnly).get("count"));
		Map<String, Object> any = service.preview(form("x", false, List.of(),
				new Rule("message", "contains", "record not found", null), new Rule("message", "contains", "timed out", null)), "24h", null);
		assertEquals(21L, only(any).get("count"), "either phrase");
		Map<String, Object> all = service.preview(form("x", true, List.of(),
				new Rule("message", "contains", "record not found", null), new Rule("service", "equals", "EAIObjMgr_enu", null)), "24h", null);
		assertEquals(4L, only(all).get("count"), "both conditions");
		assertEquals(17L, only(service.preview(form("x", false, List.of(), new Rule("message", "wildcard", "SBL-*-0*", null)), "24h", null)).get("count"));
		assertEquals(5L, only(service.preview(form("x", false, List.of(), new Rule("message", "regex", "SBL-EAI-\\d{5}", null)), "24h", null)).get("count"));
		assertEquals(6L, only(service.preview(form("x", false, List.of(), new Rule("any", "contains", "WfProcMgr", null)), "24h", null)).get("count"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> samples = (List<Map<String, Object>>) exact.get("samples");
		assertTrue(samples.size() > 0 && samples.get(0).get("message").toString().contains("Record not found"));
	}

	@Test
	void theExplorerListsTheSameEventsAsTheChart() {
		long id = ((Number) service.create(form("Data errors", false, List.of(), new Rule("message", "regex", "SBL-DAT-\\d+", null),
				new Rule("message", "wildcard", "*timed out", null))).get("id")).longValue();
		var page = query.search(new LogQuery(null, null, null, null, "24h", "timestamp", "desc", 0, 50, id));
		assertEquals(17L, page.totalElements());
		var notContains = service.create(form("Not cache", true, List.of("WARN", "ERROR"), new Rule("message", "notContains", "cache", null)));
		long id2 = ((Number) notContains.get("id")).longValue();
		assertEquals(17L, query.search(new LogQuery(null, null, null, null, "24h", "timestamp", "desc", 0, 50, id2)).totalElements());
	}

	@Test
	void invalidRulesAreExplained() {
		assertThrows(IllegalArgumentException.class, () -> service.create(form("x", false, List.of())));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> service.create(form("x", false, List.of(), new Rule("message", "regex", "SBL-(", null))));
		assertTrue(e.getMessage().contains("not valid"));
	}

	@Test
	void aSpikeOpensAnIncident() {
		service.create(new CategoryForm("Record not found", null, null, true, false, List.of(), List.of(new Rule("message", "contains", "record not found", null)),
				true, "BAR", true, 5, 60, "HIGH"));
		service.evaluateAlerts(LocalDateTime.now());
		var open = incidents.findAll();
		assertEquals(1, open.size());
		assertEquals("Category: Record not found", open.get(0).getService());
		assertEquals("HIGH", open.get(0).getSeverity());
		service.evaluateAlerts(LocalDateTime.now());
		assertEquals(1, incidents.count(), "still the same incident");
	}
}
