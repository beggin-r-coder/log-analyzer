package com.anishka.log_analyzer.category;

import java.util.List;

/** What the category editor sends. */
public record CategoryForm(String name, String description, String color, Boolean enabled, Boolean matchAll, List<String> levels,
		List<CategoryMatcher.Rule> rules, Boolean showKpi, String chartType, Boolean alertEnabled, Integer alertThreshold,
		Integer alertWindowMinutes, String alertSeverity) {
}
