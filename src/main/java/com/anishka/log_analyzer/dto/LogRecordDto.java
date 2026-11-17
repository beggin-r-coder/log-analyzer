package com.anishka.log_analyzer.dto;

import java.time.ZoneId;

import com.anishka.log_analyzer.entity.LogRecord;
import com.anishka.log_analyzer.parser.LogFormat;

public record LogRecordDto(Long id, long timestamp, String timestampIso, String level, String service, String host,
		String thread, String logger, String traceId, String clientIp, String message, String category,
		String format, String formatLabel, String rawLine) {

	public static LogRecordDto from(LogRecord r) {
		String fmt = r.getSourceFormat();
		String label;
		try {
			label = fmt == null ? null : LogFormat.valueOf(fmt).getLabel();
		} catch (IllegalArgumentException e) {
			label = fmt;
		}
		return new LogRecordDto(r.getId(), r.getTimestamp().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
				r.getTimestamp().toString(), r.getLevel(), r.getService(), r.getHost(), r.getThread(), r.getLogger(),
				r.getTraceId(), r.getClientIp(), r.getMessage(), r.getErrorCategory(), fmt, label, r.getRawLine());
	}
}
