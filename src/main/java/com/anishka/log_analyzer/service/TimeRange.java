package com.anishka.log_analyzer.service;

import java.time.Duration;

/** Selectable dashboard windows and the bucket size used for each trend chart. */
public enum TimeRange {
	M15("15m", Duration.ofMinutes(15), 1),
	H1("1h", Duration.ofHours(1), 5),
	H6("6h", Duration.ofHours(6), 15),
	H24("24h", Duration.ofHours(24), 60),
	D7("7d", Duration.ofDays(7), 360),
	ALL("all", Duration.ofDays(3650), 1440);

	private final String code;
	private final Duration duration;
	private final int bucketMinutes;

	TimeRange(String code, Duration duration, int bucketMinutes) {
		this.code = code;
		this.duration = duration;
		this.bucketMinutes = bucketMinutes;
	}

	public String code() {
		return code;
	}

	public Duration duration() {
		return duration;
	}

	public int bucketMinutes() {
		return bucketMinutes;
	}

	public static TimeRange of(String code, TimeRange fallback) {
		if (code != null) {
			for (TimeRange r : values()) {
				if (r.code.equalsIgnoreCase(code.trim())) {
					return r;
				}
			}
		}
		return fallback;
	}
}
