package com.anishka.log_analyzer.dto;

import java.util.List;

public record CategoryStat(String name, long count, double share, String severity, long lastSeen,
		List<String> services) {
}
