package com.anishka.log_analyzer.dto;

public record ServiceStat(String name, long total, long warnings, long errors, double errorRate, String status,
		long lastSeen) {
}
