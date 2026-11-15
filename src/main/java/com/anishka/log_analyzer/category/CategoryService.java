package com.anishka.log_analyzer.category;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.anishka.log_analyzer.entity.LogRecord;
import com.anishka.log_analyzer.incident.IncidentService;
import com.anishka.log_analyzer.repository.LogRecordRepository;
import com.anishka.log_analyzer.service.TimeRange;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Custom categories: stores them, counts the events matching each over time (one pass over the selected window
 * for all categories), lists the matching events, and raises incidents when a category spikes.
 */
@Service
public class CategoryService {

	private static final Logger log = LoggerFactory.getLogger(CategoryService.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final LocalDateTime BUCKET_ORIGIN = LocalDateTime.of(2000, 1, 1, 0, 0);
	private static final String[] PALETTE = { "#6366f1", "#e11d48", "#0ea5e9", "#f59e0b", "#10b981", "#8b5cf6", "#ec4899", "#14b8a6" };
	static final List<String> SEVERITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

	private final CustomCategoryRepository repo;
	private final LogRecordRepository logs;
	private final JdbcTemplate jdbc;
	private final IncidentService incidents;

	public CategoryService(CustomCategoryRepository repo, LogRecordRepository logs, JdbcTemplate jdbc, IncidentService incidents) {
		this.repo = repo;
		this.logs = logs;
		this.jdbc = jdbc;
		this.incidents = incidents;
	}

	// ------------------------------------------------------------------ CRUD

	public List<Map<String, Object>> list() {
		return repo.findAllByOrderByIdAsc().stream().map(CategoryService::dto).toList();
	}

	public Map<String, Object> create(CategoryForm f) {
		CustomCategory c = new CustomCategory();
		apply(c, f);
		if (c.getColor() == null) c.setColor(PALETTE[(int) (repo.count() % PALETTE.length)]);
		return dto(repo.save(c));
	}

	public Map<String, Object> update(long id, CategoryForm f) {
		CustomCategory c = get(id);
		apply(c, f);
		return dto(repo.save(c));
	}

	public void delete(long id) {
		repo.delete(get(id));
	}

	public CustomCategory get(long id) {
		return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("No category " + id));
	}

	private static void apply(CustomCategory c, CategoryForm f) {
		if (f.name() == null || f.name().isBlank()) throw new IllegalArgumentException("Give the category a name, e.g. \"Record not found\"");
		List<CategoryMatcher.Rule> rules = f.rules() == null ? List.of() : f.rules();
		CategoryMatcher.validate(rules);
		c.setName(cut(f.name().trim(), 80));
		c.setDescription(f.description() == null || f.description().isBlank() ? null : cut(f.description().trim(), 400));
		if (f.color() != null && f.color().matches("#[0-9a-fA-F]{6}")) c.setColor(f.color());
		if (f.enabled() != null) c.setEnabled(f.enabled());
		c.setMatchAll(Boolean.TRUE.equals(f.matchAll()));
		c.setLevels(f.levels() == null ? null : String.join(",", f.levels().stream().map(l -> l.trim().toUpperCase(Locale.ROOT)).filter(l -> !l.isEmpty()).toList()));
		c.setRulesJson(JSON.writeValueAsString(rules.stream().filter(r -> r.value() != null && !r.value().isEmpty()).toList()));
		c.setShowKpi(f.showKpi() == null || f.showKpi());
		String chart = f.chartType() == null ? CustomCategory.LINE : f.chartType().toUpperCase(Locale.ROOT);
		c.setChartType(List.of(CustomCategory.LINE, CustomCategory.BAR, CustomCategory.AREA, CustomCategory.NONE).contains(chart) ? chart : CustomCategory.LINE);
		c.setAlertEnabled(Boolean.TRUE.equals(f.alertEnabled()));
		c.setAlertThreshold(f.alertThreshold() == null ? 10 : Math.max(1, f.alertThreshold()));
		c.setAlertWindowMinutes(f.alertWindowMinutes() == null ? 15 : Math.max(1, Math.min(f.alertWindowMinutes(), 1440)));
		String sev = f.alertSeverity() == null ? "MEDIUM" : f.alertSeverity().toUpperCase(Locale.ROOT);
		c.setAlertSeverity(SEVERITIES.contains(sev) ? sev : "MEDIUM");
	}

	static List<CategoryMatcher.Rule> rules(CustomCategory c) {
		return JSON.readValue(c.getRulesJson() == null ? "[]" : c.getRulesJson(), new TypeReference<List<CategoryMatcher.Rule>>() {
		});
	}

	public static CategoryMatcher matcher(CustomCategory c) {
		return new CategoryMatcher(rules(c), c.isMatchAll(), c.getLevels());
	}

	static Map<String, Object> dto(CustomCategory c) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", c.getId());
		m.put("name", c.getName());
		m.put("description", c.getDescription());
		m.put("color", c.getColor());
		m.put("enabled", c.isEnabled());
		m.put("matchAll", c.isMatchAll());
		m.put("levels", c.getLevels() == null || c.getLevels().isBlank() ? List.of() : List.of(c.getLevels().split(",")));
		m.put("rules", rules(c));
		m.put("showKpi", c.isShowKpi());
		m.put("chartType", c.getChartType());
		m.put("alertEnabled", c.isAlertEnabled());
		m.put("alertThreshold", c.getAlertThreshold());
		m.put("alertWindowMinutes", c.getAlertWindowMinutes());
		m.put("alertSeverity", c.getAlertSeverity());
		return m;
	}

	// ------------------------------------------------------------------ counting

	private record Window(LocalDateTime from, LocalDateTime to, LocalDateTime prevFrom, int bucket, int shift, String code) {
	}

	private Window window(String rangeCode, Integer tz) {
		TimeRange range = TimeRange.of(rangeCode, TimeRange.H24);
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime from = range == TimeRange.ALL
				? logs.findTopByOrderByTimestampAsc().map(LogRecord::getTimestamp).orElse(now.minusDays(7))
				: now.minus(range.duration());
		LocalDateTime prevFrom = range == TimeRange.ALL ? from : from.minus(range.duration());
		int serverOffset = ZoneId.systemDefault().getRules().getOffset(now.atZone(ZoneId.systemDefault()).toInstant()).getTotalSeconds() / 60;
		int shift = tz == null ? 0 : tz - serverOffset;
		return new Window(from, now.plusSeconds(1), prevFrom, range.bucketMinutes(), shift, range.code());
	}

	/** Per-category accumulators for one pass. */
	private static final class Acc {
		final CustomCategory cat;
		final CategoryMatcher m;
		long count, previous, errors;
		final Map<Long, Long> buckets = new HashMap<>();
		final Map<String, Long> services = new HashMap<>();
		LocalDateTime last;
		String lastMessage;

		Acc(CustomCategory cat, CategoryMatcher m) {
			this.cat = cat;
			this.m = m;
		}
	}

	/** Counts, change vs the previous period, a series per bucket and the top services for each category. */
	public Map<String, Object> stats(String rangeCode, Integer tz, List<CustomCategory> only) {
		Window w = window(rangeCode, tz);
		List<Acc> accs = new ArrayList<>();
		for (CustomCategory c : only != null ? only : repo.findAllByOrderByIdAsc()) {
			if (only != null || c.isEnabled()) accs.add(new Acc(c, matcher(c)));
		}
		long first = bucketIndex(w.from().plusMinutes(w.shift()), w.bucket()), lastB = bucketIndex(w.to().plusMinutes(w.shift()), w.bucket());
		long[] scanned = { 0 };
		if (!accs.isEmpty()) {
			jdbc.query(con -> {
				var ps = con.prepareStatement("select log_time, log_level, service, host, trace_id, error_category, logger, message "
						+ "from log_records where log_time >= ? and log_time < ?");
				ps.setTimestamp(1, Timestamp.valueOf(w.prevFrom()));
				ps.setTimestamp(2, Timestamp.valueOf(w.to()));
				ps.setFetchSize(2000);
				return ps;
			}, rs -> {
				LocalDateTime t = rs.getTimestamp(1).toLocalDateTime();
				CategoryMatcher.Row row = new CategoryMatcher.Row(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
						rs.getString(6), rs.getString(7), rs.getString(8));
				boolean current = !t.isBefore(w.from());
				scanned[0]++;
				for (Acc a : accs) {
					if (!a.m.test(row)) continue;
					if (!current) {
						a.previous++;
						continue;
					}
					a.count++;
					if ("ERROR".equals(row.level()) || "FATAL".equals(row.level())) a.errors++;
					a.buckets.merge(bucketIndex(t.plusMinutes(w.shift()), w.bucket()), 1L, Long::sum);
					if (row.service() != null) a.services.merge(row.service(), 1L, Long::sum);
					if (a.last == null || t.isAfter(a.last)) {
						a.last = t;
						a.lastMessage = row.message();
					}
				}
			});
		}
		List<Long> times = new ArrayList<>();
		for (long b = first; b <= lastB; b++) times.add(epoch(BUCKET_ORIGIN.plusMinutes(b * w.bucket() - w.shift())));
		List<Map<String, Object>> out = new ArrayList<>();
		for (Acc a : accs) {
			Map<String, Object> m = dto(a.cat);
			m.put("count", a.count);
			m.put("previous", w.code().equals(TimeRange.ALL.code()) ? null : a.previous);
			m.put("errors", a.errors);
			List<Long> series = new ArrayList<>();
			for (long b = first; b <= lastB; b++) series.add(a.buckets.getOrDefault(b, 0L));
			m.put("series", series);
			m.put("peak", series.stream().mapToLong(Long::longValue).max().orElse(0));
			m.put("topServices", a.services.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5)
					.map(e -> Map.<String, Object>of("name", e.getKey(), "count", e.getValue())).toList());
			m.put("lastSeen", a.last == null ? null : epoch(a.last));
			m.put("lastMessage", a.lastMessage == null ? null : cut(a.lastMessage, 300));
			out.add(m);
		}
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("range", w.code());
		res.put("bucketMinutes", w.bucket());
		res.put("from", epoch(w.from()));
		res.put("to", epoch(w.to()));
		res.put("times", times);
		res.put("scanned", scanned[0]);
		res.put("categories", out);
		return res;
	}

	/** A category that is still being edited: its counts and the latest matching events. */
	public Map<String, Object> preview(CategoryForm f, String rangeCode, Integer tz) {
		CustomCategory c = new CustomCategory();
		apply(c, f.name() == null || f.name().isBlank() ? withName(f) : f);
		Map<String, Object> res = stats(rangeCode, tz, List.of(c));
		Window w = window(rangeCode, tz);
		Specification<LogRecord> spec = matcher(c).specification().and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("timestamp"), w.from()));
		res.put("samples", logs.findAll(spec, PageRequest.of(0, 8, Sort.by(Sort.Direction.DESC, "timestamp", "id"))).stream().map(r -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", r.getId());
			m.put("time", epoch(r.getTimestamp()));
			m.put("level", r.getLevel());
			m.put("service", r.getService());
			m.put("message", cut(r.getMessage() == null ? "" : r.getMessage(), 240));
			return m;
		}).toList());
		return res;
	}

	private static CategoryForm withName(CategoryForm f) {
		return new CategoryForm("Preview", f.description(), f.color(), f.enabled(), f.matchAll(), f.levels(), f.rules(), f.showKpi(),
				f.chartType(), f.alertEnabled(), f.alertThreshold(), f.alertWindowMinutes(), f.alertSeverity());
	}

	/** For the log explorer: events of this category. */
	public Specification<LogRecord> specification(long id) {
		return matcher(get(id)).specification();
	}

	// ------------------------------------------------------------------ alerts

	@Scheduled(fixedDelayString = "${app.categories.evaluate-ms:30000}", initialDelayString = "${app.incidents.initial-delay-ms:10000}")
	public void evaluateAlerts() {
		try {
			evaluateAlerts(LocalDateTime.now());
		} catch (RuntimeException e) {
			log.warn("Category alerts could not be evaluated: {}", e.toString());
		}
	}

	public void evaluateAlerts(LocalDateTime now) {
		for (CustomCategory c : repo.findAllByOrderByIdAsc()) {
			if (!c.isEnabled() || !c.isAlertEnabled()) continue;
			LocalDateTime from = now.minusMinutes(c.getAlertWindowMinutes());
			Specification<LogRecord> spec = matcher(c).specification()
					.and((root, q, cb) -> cb.between(root.get("timestamp"), from, now.plusSeconds(1)));
			long n = logs.count(spec);
			if (n >= c.getAlertThreshold()) {
				String sample = logs.findAll(spec, PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "timestamp"))).stream().findFirst()
						.map(LogRecord::getMessage).orElse(null);
				incidents.categoryBreach(c.getName(), c.getAlertSeverity(), n, c.getAlertThreshold(), c.getAlertWindowMinutes(), sample, now);
			}
		}
	}

	// ------------------------------------------------------------------

	private static long bucketIndex(LocalDateTime t, int bucket) {
		return java.time.Duration.between(BUCKET_ORIGIN, t).toMinutes() / bucket;
	}

	private static long epoch(LocalDateTime t) {
		return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	private static String cut(String s, int max) {
		return s.length() > max ? s.substring(0, max - 1) + "…" : s;
	}
}
