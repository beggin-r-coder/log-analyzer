package com.anishka.log_analyzer.parser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Detects and parses log lines in many common formats into a single
 * normalised {@link ParsedLog}. Formats are tried from most to least specific;
 * anything unrecognised falls through to a best-effort generic parser, so no
 * line is ever dropped.
 */
@Component
public class UniversalLogParser {

	// ---------- building blocks ----------
	private static final String TS_ISO = "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?";
	private static final String TS_SLASH = "\\d{4}/\\d{2}/\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?";
	private static final String TS_TIME_ONLY = "\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?";
	private static final String TS_ANY = "(?:" + TS_ISO + "|" + TS_SLASH + ")";
	private static final String LEVEL = "(?:TRACE|DEBUG|INFO|NOTICE|WARN(?:ING)?|ERROR|ERR|FATAL|CRIT(?:ICAL)?|SEVERE|ALERT|EMERG|DBG|WRN|INF|FTL)";

	// ---------- format patterns ----------
	private static final Pattern SPRING_BOOT = Pattern.compile("^(" + TS_ANY + ")\\s+(" + LEVEL
			+ ")\\s+(\\d+)\\s+---\\s+(?:\\[([^\\]]+)\\]\\s+)?\\[\\s*([^\\]]+)\\]\\s+(\\S+)\\s+:\\s+(.*)$");

	private static final Pattern BRACKETED = Pattern
			.compile("^(" + TS_ANY + ")\\s+\\[(" + LEVEL + ")\\]\\s+\\[(.*?)\\]\\s+\\[(.*?)\\]\\s+\\[(.*?)\\]\\s+(.*)$");

	private static final Pattern LOG4J_LEVEL_FIRST = Pattern
			.compile("^(" + TS_ANY + ")\\s+(" + LEVEL + ")\\s+\\[([^\\]]+)\\]\\s+(\\S+)\\s+-\\s+(.*)$");

	private static final Pattern LOG4J_THREAD_FIRST = Pattern.compile("^(" + TS_ANY + "|" + TS_TIME_ONLY
			+ ")\\s+\\[([^\\]]+)\\]\\s+(" + LEVEL + ")\\s+(\\S+)\\s+-\\s+(.*)$");

	private static final Pattern PYTHON = Pattern
			.compile("^(" + TS_ANY + ")\\s+-\\s+(\\S+)\\s+-\\s+(" + LEVEL + ")\\s+-\\s+(.*)$");

	private static final Pattern PIPE = Pattern
			.compile("^(" + TS_ANY + ")\\s*\\|\\s*(" + LEVEL + ")\\s*\\|\\s*([^|]+?)\\s*\\|\\s*(.*)$");

	private static final Pattern ACCESS_LOG = Pattern.compile(
			"^(\\S+)\\s+\\S+\\s+(\\S+)\\s+\\[([^\\]]+)\\]\\s+\"([A-Z]+)\\s+(\\S+)[^\"]*\"\\s+(\\d{3})\\s+(\\S+)(?:\\s+\"([^\"]*)\"\\s+\"([^\"]*)\")?(.*)$");

	private static final Pattern APACHE_ERROR = Pattern.compile(
			"^\\[(\\w{3} \\w{3} \\d{1,2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)? \\d{4})\\]\\s+\\[(?:([\\w-]+):)?(\\w+)\\]\\s+(?:\\[pid (\\d+)[^\\]]*\\]\\s+)?(?:\\[client ([^\\]]+)\\]\\s+)?(.*)$");

	private static final Pattern SYSLOG_5424 = Pattern
			.compile("^<(\\d{1,3})>1\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(-|\\[.*?\\])\\s*(.*)$");

	private static final Pattern SYSLOG_3164 = Pattern.compile(
			"^(?:<(\\d{1,3})>)?([A-Z][a-z]{2}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+(\\S+)\\s+([\\w./-]+?)(?:\\[(\\d+)\\])?:\\s+(.*)$");

	private static final Pattern LOGFMT_PAIR = Pattern.compile("([A-Za-z_@][\\w.@-]*)=(\"(?:[^\"\\\\]|\\\\.)*\"|\\S*)");

	// ---------- extraction helpers ----------
	private static final Pattern ANY_TIMESTAMP = Pattern.compile("(" + TS_ANY
			+ ")|(\\d{2}/[A-Z][a-z]{2}/\\d{4}:\\d{2}:\\d{2}:\\d{2}(?: [+-]\\d{4})?)|([A-Z][a-z]{2}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2})|(\\d{2}-\\d{2}-\\d{4} \\d{2}:\\d{2}:\\d{2})");
	private static final Pattern LEVEL_TOKEN = Pattern.compile("(?<![\\w-])(" + LEVEL + ")(?![\\w-])");
	private static final Pattern LEADING_LEVEL = Pattern.compile("^\\[?(" + LEVEL + ")\\]?[:\\s]+(.*)$",
			Pattern.DOTALL);
	private static final Pattern TRACE_ID = Pattern.compile(
			"(?i)\\b(?:trace[_-]?id|request[_-]?id|req[_-]?id|correlation[_-]?id|txn[_-]?id|x-request-id|rrn)\\s*[=:]\\s*\"?([\\w-]{6,64})");
	private static final Pattern IPV4 = Pattern.compile("\\b((?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3})\\b");
	private static final Pattern CONTINUATION = Pattern
			.compile("^(?:\\s+at\\s|\\s+\\.\\.\\.\\s*\\d+\\s+more|Caused by:|\\s{2,}\\S|\\t)");

	private static final DateTimeFormatter ACCESS_TS = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z",
			Locale.ENGLISH);
	private static final DateTimeFormatter APACHE_TS = new DateTimeFormatterBuilder()
			.appendPattern("EEE MMM d HH:mm:ss").optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
			.optionalEnd().appendPattern(" yyyy").toFormatter(Locale.ENGLISH);
	private static final DateTimeFormatter SYSLOG_TS = new DateTimeFormatterBuilder().parseCaseInsensitive()
			.appendPattern("MMM d HH:mm:ss").parseDefaulting(ChronoField.YEAR, 2000).toFormatter(Locale.ENGLISH);
	private static final DateTimeFormatter DMY_TS = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

	private final JsonMapper json = JsonMapper.builder().build();

	// =====================================================================
	// PUBLIC API
	// =====================================================================

	/**
	 * Parses a block of text that may contain many events, including
	 * multi-line events such as Java stack traces.
	 */
	public List<ParsedLog> parse(String text, String defaultService) {
		List<ParsedLog> out = new ArrayList<>();
		if (text == null) {
			return out;
		}
		ParsedLog current = null;
		for (String line : text.split("\\r?\\n")) {
			if (line.isBlank()) {
				continue;
			}
			if (current != null && CONTINUATION.matcher(line).find()) {
				current.appendContinuation(line.stripTrailing());
				continue;
			}
			current = parseLine(line, defaultService).orElse(null);
			if (current != null) {
				out.add(current);
			}
		}
		return out;
	}

	/** Parses a single line. Returns empty only for blank input. */
	public Optional<ParsedLog> parseLine(String rawLine, String defaultService) {
		if (rawLine == null || rawLine.isBlank()) {
			return Optional.empty();
		}
		String line = rawLine.strip();
		ParsedLog p = tryJson(line);
		if (p == null) p = trySpringBoot(line);
		if (p == null) p = tryBracketed(line);
		if (p == null) p = tryLog4j(line);
		if (p == null) p = tryPython(line);
		if (p == null) p = tryPipe(line);
		if (p == null) p = tryAccessLog(line);
		if (p == null) p = tryApacheError(line);
		if (p == null) p = trySyslog5424(line);
		if (p == null) p = trySyslog3164(line);
		if (p == null) p = tryLogfmt(line);
		if (p == null) p = parseGeneric(line);

		p.rawLine(line);
		enrich(p, defaultService);
		return Optional.of(p);
	}

	// =====================================================================
	// FORMAT HANDLERS
	// =====================================================================

	private ParsedLog tryJson(String line) {
		if (!line.startsWith("{") || !line.endsWith("}")) {
			return null;
		}
		Map<String, Object> flat;
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> map = json.readValue(line, Map.class);
			flat = new LinkedHashMap<>();
			flatten("", map, flat);
		} catch (RuntimeException e) {
			return null;
		}
		Object ts = first(flat, "@timestamp", "timestamp", "time", "ts", "datetime", "date", "eventtime", "t");
		String level = str(first(flat, "level", "log.level", "severity", "loglevel", "lvl", "levelname", "severity_text"));
		return new ParsedLog().format(LogFormat.JSON)
				.timestamp(ts instanceof Number n ? fromEpoch(n.longValue()) : parseTimestamp(str(ts)))
				.level(normalizeLevel(level))
				.message(str(first(flat, "message", "msg", "log", "text", "event", "error.message")))
				.service(str(first(flat, "service", "service.name", "app", "application", "appname", "component")))
				.host(str(first(flat, "host", "hostname", "host.name", "pod")))
				.thread(str(first(flat, "thread", "thread_name", "threadname")))
				.logger(str(first(flat, "logger", "logger_name", "loggername", "log.logger", "name")))
				.traceId(str(first(flat, "traceid", "trace_id", "trace.id", "requestid", "request_id", "correlationid",
						"correlation_id", "txnid", "x-request-id")))
				.clientIp(str(first(flat, "client_ip", "clientip", "ip", "remote_addr", "client.ip", "source.ip")));
	}

	private ParsedLog trySpringBoot(String line) {
		Matcher m = SPRING_BOOT.matcher(line);
		if (!m.matches()) {
			return null;
		}
		return new ParsedLog().format(LogFormat.SPRING_BOOT).timestamp(parseTimestamp(m.group(1)))
				.level(normalizeLevel(m.group(2))).service(m.group(4)).thread(m.group(5)).logger(m.group(6))
				.message(m.group(7));
	}

	private ParsedLog tryBracketed(String line) {
		Matcher m = BRACKETED.matcher(line);
		if (!m.matches()) {
			return null;
		}
		return new ParsedLog().format(LogFormat.BRACKETED).timestamp(parseTimestamp(m.group(1)))
				.level(normalizeLevel(m.group(2))).service(m.group(3)).traceId(m.group(4)).clientIp(m.group(5))
				.message(m.group(6));
	}

	private ParsedLog tryLog4j(String line) {
		Matcher m = LOG4J_LEVEL_FIRST.matcher(line);
		if (m.matches()) {
			return new ParsedLog().format(LogFormat.LOG4J).timestamp(parseTimestamp(m.group(1)))
					.level(normalizeLevel(m.group(2))).thread(m.group(3)).logger(m.group(4)).message(m.group(5));
		}
		m = LOG4J_THREAD_FIRST.matcher(line);
		if (m.matches()) {
			return new ParsedLog().format(LogFormat.LOG4J).timestamp(parseTimestamp(m.group(1)))
					.thread(m.group(2)).level(normalizeLevel(m.group(3))).logger(m.group(4)).message(m.group(5));
		}
		return null;
	}

	private ParsedLog tryPython(String line) {
		Matcher m = PYTHON.matcher(line);
		if (!m.matches()) {
			return null;
		}
		return new ParsedLog().format(LogFormat.PYTHON).timestamp(parseTimestamp(m.group(1))).logger(m.group(2))
				.service(m.group(2)).level(normalizeLevel(m.group(3))).message(m.group(4));
	}

	private ParsedLog tryPipe(String line) {
		Matcher m = PIPE.matcher(line);
		if (!m.matches()) {
			return null;
		}
		return new ParsedLog().format(LogFormat.PIPE_DELIMITED).timestamp(parseTimestamp(m.group(1)))
				.level(normalizeLevel(m.group(2))).service(m.group(3)).message(m.group(4));
	}

	private ParsedLog tryAccessLog(String line) {
		Matcher m = ACCESS_LOG.matcher(line);
		if (!m.matches()) {
			return null;
		}
		LocalDateTime ts;
		try {
			ts = ZonedDateTime.parse(m.group(3), ACCESS_TS).withZoneSameInstant(ZoneId.systemDefault())
					.toLocalDateTime();
		} catch (DateTimeParseException e) {
			return null;
		}
		int status = Integer.parseInt(m.group(6));
		String level = status >= 500 ? "ERROR" : status >= 400 ? "WARN" : "INFO";
		String bytes = m.group(7);
		String extra = m.group(10) == null ? "" : m.group(10).trim();
		String msg = m.group(4) + " " + m.group(5) + " -> HTTP " + status
				+ ("-".equals(bytes) ? "" : " (" + bytes + " bytes)") + (extra.isEmpty() ? "" : " " + extra);
		return new ParsedLog().format(LogFormat.ACCESS_LOG).timestamp(ts).level(level).clientIp(m.group(1))
				.message(msg);
	}

	private ParsedLog tryApacheError(String line) {
		Matcher m = APACHE_ERROR.matcher(line);
		if (!m.matches()) {
			return null;
		}
		LocalDateTime ts;
		try {
			ts = LocalDateTime.parse(m.group(1).replaceAll("\\s+", " "), APACHE_TS);
		} catch (DateTimeParseException e) {
			return null;
		}
		String client = m.group(5);
		if (client != null && client.contains(":") && client.indexOf(':') == client.lastIndexOf(':')) {
			client = client.substring(0, client.indexOf(':'));
		}
		return new ParsedLog().format(LogFormat.APACHE_ERROR).timestamp(ts).logger(m.group(2))
				.level(normalizeLevel(m.group(3))).thread(m.group(4)).clientIp(client).service("apache-httpd")
				.message(m.group(6));
	}

	private ParsedLog trySyslog5424(String line) {
		Matcher m = SYSLOG_5424.matcher(line);
		if (!m.matches()) {
			return null;
		}
		String msg = m.group(8);
		String level = levelFromPri(m.group(1));
		Matcher lead = LEADING_LEVEL.matcher(msg);
		if (lead.matches()) {
			level = normalizeLevel(lead.group(1));
			msg = lead.group(2);
		}
		return new ParsedLog().format(LogFormat.SYSLOG_RFC5424).timestamp(parseTimestamp(m.group(2)))
				.host(m.group(3)).service(m.group(4)).thread(m.group(5)).level(level).message(msg);
	}

	private ParsedLog trySyslog3164(String line) {
		Matcher m = SYSLOG_3164.matcher(line);
		if (!m.matches()) {
			return null;
		}
		LocalDateTime ts;
		try {
			ts = LocalDateTime.parse(m.group(2).replaceAll("\\s+", " "), SYSLOG_TS)
					.withYear(Year.now().getValue());
			if (ts.isAfter(LocalDateTime.now().plusDays(1))) {
				ts = ts.minusYears(1); // e.g. a "Dec 31" line read on Jan 1
			}
		} catch (DateTimeParseException e) {
			return null;
		}
		String msg = m.group(6);
		String level = m.group(1) != null ? levelFromPri(m.group(1)) : null;
		Matcher lead = LEADING_LEVEL.matcher(msg);
		if (lead.matches()) {
			level = normalizeLevel(lead.group(1));
			msg = lead.group(2);
		}
		if (level == null) {
			level = guessLevel(msg);
		}
		return new ParsedLog().format(LogFormat.SYSLOG_RFC3164).timestamp(ts).host(m.group(3)).service(m.group(4))
				.thread(m.group(5)).level(level).message(msg);
	}

	private ParsedLog tryLogfmt(String line) {
		if (!LOGFMT_PAIR.matcher(line).lookingAt()) {
			return null;
		}
		Map<String, Object> kv = new LinkedHashMap<>();
		Matcher m = LOGFMT_PAIR.matcher(line);
		while (m.find()) {
			String v = m.group(2);
			if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
				v = v.substring(1, v.length() - 1).replace("\\\"", "\"");
			}
			kv.put(m.group(1).toLowerCase(Locale.ROOT), v);
		}
		if (kv.size() < 2 || (first(kv, "msg", "message") == null && first(kv, "level", "lvl", "severity") == null)) {
			return null;
		}
		return new ParsedLog().format(LogFormat.LOGFMT)
				.timestamp(parseTimestamp(str(first(kv, "ts", "time", "timestamp", "t", "@timestamp"))))
				.level(normalizeLevel(str(first(kv, "level", "lvl", "severity"))))
				.message(str(first(kv, "msg", "message", "error", "err")))
				.service(str(first(kv, "service", "app", "svc", "component")))
				.host(str(first(kv, "host", "hostname", "pod")))
				.thread(str(first(kv, "thread", "goroutine")))
				.logger(str(first(kv, "logger", "caller", "source")))
				.traceId(str(first(kv, "trace_id", "traceid", "request_id", "req_id", "correlation_id", "txn_id")))
				.clientIp(str(first(kv, "client_ip", "ip", "remote_addr", "src_ip")));
	}

	private ParsedLog parseGeneric(String line) {
		ParsedLog p = new ParsedLog().format(LogFormat.GENERIC);
		String rest = line;

		Matcher tm = ANY_TIMESTAMP.matcher(line);
		if (tm.find()) {
			p.timestamp(parseLooseTimestamp(tm));
			rest = rest.substring(0, tm.start()) + rest.substring(tm.end());
		}
		Matcher lm = LEVEL_TOKEN.matcher(rest);
		if (lm.find()) {
			p.level(normalizeLevel(lm.group(1)));
			rest = rest.substring(0, lm.start()) + rest.substring(lm.end());
		} else {
			p.level(guessLevel(line));
		}
		// tidy leftover separators such as "[] - :" around removed tokens
		rest = rest.replaceAll("^[\\s\\[\\]|:\\-,]+", "").replaceAll("\\[\\s*\\]", "").replaceAll("\\s{2,}", " ")
				.trim();
		return p.message(rest.isEmpty() ? line : rest);
	}

	// =====================================================================
	// NORMALISATION
	// =====================================================================

	private void enrich(ParsedLog p, String defaultService) {
		if (p.getTimestamp() == null) {
			p.timestamp(LocalDateTime.now());
		}
		if (p.getLevel() == null) {
			p.level(guessLevel(p.getMessage()));
		}
		if (p.getService() == null) {
			p.service(defaultService != null ? defaultService : "unknown");
		}
		String msg = p.getMessage() == null ? "" : p.getMessage();
		if (p.getTraceId() == null) {
			Matcher m = TRACE_ID.matcher(msg);
			if (m.find()) {
				p.traceId(m.group(1));
			}
		}
		if (p.getClientIp() == null) {
			Matcher m = IPV4.matcher(msg);
			if (m.find()) {
				p.clientIp(m.group(1));
			}
		}
	}

	/** Maps the many level spellings used in the wild onto six canonical levels. */
	public static String normalizeLevel(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String l = raw.trim().toUpperCase(Locale.ROOT);
		if (l.startsWith("TRACE")) return "TRACE";
		return switch (l) {
		case "DEBUG", "DBG", "FINE", "FINER", "FINEST", "VERBOSE" -> "DEBUG";
		case "INFO", "INF", "INFORMATION", "NOTICE", "CONFIG" -> "INFO";
		case "WARN", "WARNING", "WRN" -> "WARN";
		case "ERROR", "ERR", "SEVERE" -> "ERROR";
		case "FATAL", "FTL", "CRIT", "CRITICAL", "ALERT", "EMERG", "EMERGENCY", "PANIC" -> "FATAL";
		default -> null;
		};
	}

	public static int levelRank(String level) {
		if (level == null) return 2;
		return switch (level) {
		case "TRACE" -> 0;
		case "DEBUG" -> 1;
		case "WARN" -> 3;
		case "ERROR" -> 4;
		case "FATAL" -> 5;
		default -> 2;
		};
	}

	private static String levelFromPri(String pri) {
		int severity = Integer.parseInt(pri) % 8;
		return switch (severity) {
		case 0, 1, 2 -> "FATAL";
		case 3 -> "ERROR";
		case 4 -> "WARN";
		case 7 -> "DEBUG";
		default -> "INFO";
		};
	}

	private static String guessLevel(String text) {
		String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
		if (t.contains("fatal") || t.contains("panic")) return "FATAL";
		if (t.contains("exception") || t.contains("error") || t.contains("failed") || t.contains("failure"))
			return "ERROR";
		if (t.contains("warn") || t.contains("retry") || t.contains("deprecated")) return "WARN";
		return "INFO";
	}

	/** Parses ISO-8601-ish timestamps; zoned values are converted to server-local time. */
	static LocalDateTime parseTimestamp(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String s = raw.trim();
		if (s.matches("\\d{10}(\\d{3})?")) {
			return fromEpoch(Long.parseLong(s));
		}
		if (s.matches(TS_TIME_ONLY)) {
			return LocalDate.now().atTime(LocalTime.parse(s.replace(',', '.')));
		}
		s = s.replace('/', '-').replace(',', '.');
		if (s.length() > 10 && s.charAt(10) == ' ') {
			s = s.substring(0, 10) + 'T' + s.substring(11);
		}
		s = s.replaceFirst("([+-]\\d{2})(\\d{2})$", "$1:$2");
		try {
			if (s.endsWith("Z") || s.matches(".*[+-]\\d{2}:\\d{2}$")) {
				return OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
			}
			return LocalDateTime.parse(s);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static LocalDateTime parseLooseTimestamp(Matcher tm) {
		try {
			if (tm.group(1) != null) {
				return parseTimestamp(tm.group(1));
			}
			if (tm.group(2) != null) {
				String v = tm.group(2);
				return v.length() > 20
						? ZonedDateTime.parse(v, ACCESS_TS).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
						: LocalDateTime.parse(v, DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss", Locale.ENGLISH));
			}
			if (tm.group(3) != null) {
				return LocalDateTime.parse(tm.group(3).replaceAll("\\s+", " "), SYSLOG_TS)
						.withYear(Year.now().getValue());
			}
			if (tm.group(4) != null) {
				return LocalDateTime.parse(tm.group(4), DMY_TS);
			}
		} catch (DateTimeParseException e) {
			// fall through
		}
		return null;
	}

	private static LocalDateTime fromEpoch(long v) {
		Instant i = v > 100_000_000_000L ? Instant.ofEpochMilli(v) : Instant.ofEpochSecond(v);
		return LocalDateTime.ofInstant(i, ZoneId.systemDefault());
	}

	@SuppressWarnings("unchecked")
	private static void flatten(String prefix, Map<String, Object> in, Map<String, Object> out) {
		in.forEach((k, v) -> {
			String key = (prefix + k).toLowerCase(Locale.ROOT);
			if (v instanceof Map<?, ?> nested) {
				flatten(key + ".", (Map<String, Object>) nested, out);
			} else {
				out.putIfAbsent(key, v);
			}
		});
	}

	private static Object first(Map<String, Object> map, String... keys) {
		for (String k : keys) {
			Object v = map.get(k);
			if (v != null && !(v instanceof String s && s.isBlank())) {
				return v;
			}
		}
		return null;
	}

	private static String str(Object o) {
		return o == null ? null : String.valueOf(o);
	}
}
