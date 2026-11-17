package com.anishka.log_analyzer.source;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.anishka.log_analyzer.parser.LogFormat;
import com.anishka.log_analyzer.parser.ParsedLog;
import com.anishka.log_analyzer.parser.UniversalLogParser;

/** Turns one row of a database table or spreadsheet into log events, using the source's column mapping. */
@Component
public class RowMapper {

	private final UniversalLogParser parser;

	public RowMapper(UniversalLogParser parser) {
		this.parser = parser;
	}

	/** @param value the value of a column by name (case-insensitive), or null */
	public List<ParsedLog> map(LogSource src, Function<String, Object> value, LogFormat format) {
		String service = str(value, src.getServiceColumn());
		if (service == null || service.isBlank()) {
			service = notBlank(src.getDefaultService()) ? src.getDefaultService() : src.getName();
		}
		LocalDateTime ts = notBlank(src.getTsColumn()) ? Timestamps.parse(value.apply(src.getTsColumn())) : null;
		if (notBlank(src.getRawColumn())) {
			String raw = str(value, src.getRawColumn());
			List<ParsedLog> parsed = parser.parse(raw == null ? "" : raw, service);
			if (ts != null) parsed.forEach(p -> p.timestamp(ts));
			return parsed;
		}
		ParsedLog p = new ParsedLog().format(format).service(service.length() > 64 ? service.substring(0, 64) : service);
		String msg = str(value, src.getMessageColumn());
		p.message(msg == null ? "" : msg);
		p.level(level(str(value, src.getLevelColumn()), p.getMessage()));
		if (notBlank(src.getTraceColumn())) p.traceId(str(value, src.getTraceColumn()));
		if (notBlank(src.getHostColumn())) p.host(str(value, src.getHostColumn()));
		p.rawLine(p.getMessage());
		p.timestamp(ts != null ? ts : LocalDateTime.now());
		return List.of(p);
	}

	/** Text levels (ERROR, warning…) or numeric Siebel-style severities 0-5. */
	static String level(String raw, String message) {
		if (raw != null && raw.strip().matches("[0-5](\\.0)?")) {
			return UniversalLogParser.siebelLevel("GenericLog", "Generic", Integer.parseInt(raw.strip().substring(0, 1)), message == null ? "" : message);
		}
		String l = UniversalLogParser.normalizeLevel(raw);
		return l != null ? l : UniversalLogParser.normalizeLevel(guess(message));
	}

	private static String guess(String message) {
		String m = message == null ? "" : message.toLowerCase();
		if (m.contains("fatal") || m.contains("crash")) return "FATAL";
		if (m.contains("error") || m.contains("exception") || m.contains("failed") || m.matches(".*\\bsbl-[a-z]{3}-\\d{5}.*")) return "ERROR";
		if (m.contains("warn")) return "WARN";
		return "INFO";
	}

	private static String str(Function<String, Object> value, String column) {
		if (!notBlank(column)) return null;
		Object o = value.apply(column);
		if (o == null) return null;
		if (o instanceof Double d && d == Math.rint(d)) return String.valueOf(d.longValue());
		return o.toString();
	}

	static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}
}
