package com.anishka.log_analyzer.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.anishka.log_analyzer.dto.CategoryStat;
import com.anishka.log_analyzer.dto.DashboardSummary;
import com.anishka.log_analyzer.dto.FormatStat;
import com.anishka.log_analyzer.dto.LevelTotals;
import com.anishka.log_analyzer.dto.ServiceStat;
import com.anishka.log_analyzer.dto.TrendPoint;
import com.anishka.log_analyzer.parser.LogFormat;
import com.anishka.log_analyzer.repository.LogRecordRepository;
import com.anishka.log_analyzer.simulator.LiveLogSimulator;

/** Aggregates persisted logs into the KPIs, trend and breakdowns shown on the dashboard. */
@Service
@Transactional(readOnly = true)
public class DashboardService {

	/** Origin used by the bucketing SQL in {@link LogRecordRepository#bucketedCounts}. */
	private static final LocalDateTime BUCKET_ORIGIN = LocalDateTime.of(2000, 1, 1, 0, 0);

	private final LogRecordRepository repository;
	private final LiveLogSimulator simulator;

	public DashboardService(LogRecordRepository repository, LiveLogSimulator simulator) {
		this.repository = repository;
		this.simulator = simulator;
	}

	public DashboardSummary summary(String rangeCode, Integer clientUtcOffsetMinutes) {
		TimeRange range = TimeRange.of(rangeCode, TimeRange.H24);
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime to = now.plusMinutes(1);
		LocalDateTime from = now.minus(range.duration());
		if (range == TimeRange.ALL) {
			from = earliestOr(now.minusDays(7));
		}

		LevelTotals totals = totals(from, to);
		double total = Math.max(1, totals.total());
		double errorRate = totals.total() == 0 ? 0 : totals.errorsAndFatal() * 100.0 / total;
		double warnRate = totals.total() == 0 ? 0 : totals.warn() * 100.0 / total;
		// errors weigh five times as much as warnings
		double health = totals.total() == 0 ? 100 : clamp(100 - (errorRate * 5 + warnRate));

		double hours = Math.max(1.0 / 60, java.time.Duration.between(from, now).toMinutes() / 60.0);
		// shift buckets so that e.g. hourly buckets start on the viewer's hour boundaries
		int serverOffset = ZoneId.systemDefault().getRules().getOffset(now.atZone(ZoneId.systemDefault()).toInstant())
				.getTotalSeconds() / 60;
		int shift = clientUtcOffsetMinutes == null ? 0 : clientUtcOffsetMinutes - serverOffset;
		List<TrendPoint> trend = trend(from, to, now, range.bucketMinutes(), shift);
		List<CategoryStat> categories = categories(from, to, totals.errorsAndFatal(), hours);
		List<ServiceStat> services = services(from, to);

		LocalDateTime latest = repository.latestTimestamp();
		return new DashboardSummary(range.code(), range.bucketMinutes(), epoch(from), epoch(now), totals,
				round2(health), round2(errorRate), round2(warnRate), trend, categories, services, formats(from, to),
				latest == null ? null : epoch(latest), System.currentTimeMillis(), simulator.status());
	}

	private LevelTotals totals(LocalDateTime from, LocalDateTime to) {
		Map<String, Long> m = new HashMap<>();
		for (Object[] row : repository.countByLevel(from, to)) {
			m.put((String) row[0], ((Number) row[1]).longValue());
		}
		long total = m.values().stream().mapToLong(Long::longValue).sum();
		return new LevelTotals(total, m.getOrDefault("TRACE", 0L), m.getOrDefault("DEBUG", 0L),
				m.getOrDefault("INFO", 0L), m.getOrDefault("WARN", 0L), m.getOrDefault("ERROR", 0L),
				m.getOrDefault("FATAL", 0L));
	}

	/** Continuous series (empty buckets filled with 0) so the chart has a true time axis. */
	private List<TrendPoint> trend(LocalDateTime from, LocalDateTime to, LocalDateTime now, int bucket, int shift) {
		Map<Long, long[]> byBucket = new HashMap<>(); // [total, warn, error]
		for (Object[] row : repository.bucketedCounts(from, to, bucket, shift)) {
			long b = ((Number) row[0]).longValue();
			int rank = ((Number) row[1]).intValue();
			long c = ((Number) row[2]).longValue();
			long[] v = byBucket.computeIfAbsent(b, k -> new long[3]);
			v[0] += c;
			if (rank == 3) v[1] += c;
			if (rank >= 4) v[2] += c;
		}
		long first = bucketIndex(from.plusMinutes(shift), bucket);
		long last = bucketIndex(now.plusMinutes(shift), bucket);
		List<TrendPoint> out = new ArrayList<>();
		for (long b = first; b <= last; b++) {
			long[] v = byBucket.getOrDefault(b, new long[3]);
			out.add(new TrendPoint(epoch(BUCKET_ORIGIN.plusMinutes(b * bucket - shift)), v[0], v[1], v[2]));
		}
		return out;
	}

	private List<CategoryStat> categories(LocalDateTime from, LocalDateTime to, long totalErrors, double hours) {
		Map<String, TreeSet<String>> servicesByCategory = new HashMap<>();
		Map<String, Map<String, Long>> serviceCounts = new HashMap<>();
		for (Object[] row : repository.errorCategoryServices(from, to)) {
			String cat = row[0] == null ? ErrorClassifier.OTHER : (String) row[0];
			serviceCounts.computeIfAbsent(cat, k -> new HashMap<>()).merge((String) row[1],
					((Number) row[2]).longValue(), Long::sum);
		}
		serviceCounts.forEach((cat, m) -> {
			TreeSet<String> s = new TreeSet<>(Comparator.comparing((String k) -> -m.get(k)).thenComparing(k -> k));
			s.addAll(m.keySet());
			servicesByCategory.put(cat, s);
		});

		List<CategoryStat> out = new ArrayList<>();
		for (Object[] row : repository.errorCategories(from, to)) {
			String cat = row[0] == null ? ErrorClassifier.OTHER : (String) row[0];
			long count = ((Number) row[1]).longValue();
			double perHour = count / hours;
			String severity = perHour >= 30 ? "CRITICAL" : perHour >= 10 ? "HIGH" : perHour >= 3 ? "MEDIUM" : "LOW";
			out.add(new CategoryStat(cat, count, totalErrors == 0 ? 0 : round2(count * 100.0 / totalErrors), severity,
					epoch((LocalDateTime) row[2]),
					new ArrayList<>(servicesByCategory.getOrDefault(cat, new TreeSet<>()))));
		}
		return out;
	}

	private List<ServiceStat> services(LocalDateTime from, LocalDateTime to) {
		List<ServiceStat> out = new ArrayList<>();
		for (Object[] row : repository.serviceStats(from, to)) {
			long total = ((Number) row[1]).longValue();
			long warns = row[2] == null ? 0 : ((Number) row[2]).longValue();
			long errors = row[3] == null ? 0 : ((Number) row[3]).longValue();
			double rate = total == 0 ? 0 : errors * 100.0 / total;
			String status = rate >= 10 ? "CRITICAL" : rate >= 3 ? "DEGRADED" : "HEALTHY";
			out.add(new ServiceStat((String) row[0], total, warns, errors, round2(rate), status,
					epoch((LocalDateTime) row[4])));
		}
		out.sort(Comparator.comparingDouble(ServiceStat::errorRate).reversed());
		return out;
	}

	private List<FormatStat> formats(LocalDateTime from, LocalDateTime to) {
		List<FormatStat> out = new ArrayList<>();
		for (Object[] row : repository.formatCounts(from, to)) {
			String f = (String) row[0];
			String label;
			try {
				label = LogFormat.valueOf(f).getLabel();
			} catch (IllegalArgumentException | NullPointerException e) {
				label = String.valueOf(f);
			}
			out.add(new FormatStat(f, label, ((Number) row[1]).longValue()));
		}
		return out;
	}

	public Map<String, Object> facets() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("levels", List.of("FATAL", "ERROR", "WARN", "INFO", "DEBUG", "TRACE"));
		m.put("services", repository.distinctServices());
		m.put("categories", repository.distinctCategories());
		return m;
	}

	private LocalDateTime earliestOr(LocalDateTime fallback) {
		return repository.findTopByOrderByTimestampAsc().map(r -> r.getTimestamp()).orElse(fallback);
	}

	private static long bucketIndex(LocalDateTime t, int bucket) {
		return java.time.Duration.between(BUCKET_ORIGIN, t).toMinutes() / bucket;
	}

	static long epoch(LocalDateTime t) {
		return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	private static double clamp(double v) {
		return Math.max(0, Math.min(100, v));
	}

	private static double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
