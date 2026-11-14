package com.anishka.log_analyzer.dto;

/** One time bucket. {@code t} is the bucket start as epoch millis so the browser can format it locally. */
public record TrendPoint(long t, long total, long warnings, long errors) {
}
