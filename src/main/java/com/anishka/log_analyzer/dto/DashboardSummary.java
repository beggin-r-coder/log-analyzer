package com.anishka.log_analyzer.dto;

import java.util.List;
import java.util.Map;

/** Everything the dashboard needs for one refresh, for the selected time range. */
public record DashboardSummary(String range, int bucketMinutes, long from, long to, LevelTotals totals,
		double healthScore, double errorRate, double warnRate, List<TrendPoint> trend, List<CategoryStat> categories,
		List<ServiceStat> services, List<FormatStat> formats, Long lastEventAt, long generatedAt, Map<String, Object> simulator) {
}
