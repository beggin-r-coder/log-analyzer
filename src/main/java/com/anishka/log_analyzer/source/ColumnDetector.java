package com.anishka.log_analyzer.source;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.anishka.log_analyzer.parser.LogFormat;
import com.anishka.log_analyzer.parser.ParsedLog;
import com.anishka.log_analyzer.parser.UniversalLogParser;

/**
 * Works out what each column of a table or spreadsheet holds by looking at the values themselves (column names
 * only break ties): dates/times, log levels or Siebel severities, free-text messages, complete log lines,
 * trace / SARM ids, host names, component names and increasing row ids.
 */
@Component
public class ColumnDetector {

	public static final List<String> ROLES = List.of("tsColumn", "rawColumn", "levelColumn", "messageColumn", "traceColumn",
			"idColumn", "hostColumn", "serviceColumn");
	private static final Map<String, Double> THRESHOLD = Map.of("tsColumn", .6, "rawColumn", .6, "levelColumn", .5,
			"messageColumn", .3, "traceColumn", .55, "idColumn", .7, "hostColumn", .55, "serviceColumn", .45);
	private static final Map<String, Pattern> NAME_HINTS = Map.of(
			"tsColumn", Pattern.compile("(?i).*(timestamp|time|date|created|logged|_dt|_ts|^ts$|when).*"),
			"levelColumn", Pattern.compile("(?i).*(level|severity|^sev|priority|loglevel|status_type).*"),
			"messageColumn", Pattern.compile("(?i).*(message|^msg|text|description|details|error_desc|summary).*"),
			"serviceColumn", Pattern.compile("(?i).*(component|service|application|^app|module|source|comp_name|system).*"),
			"traceColumn", Pattern.compile("(?i).*(sarm|trace|correlation|request_?id|session|txn|transaction).*"),
			"hostColumn", Pattern.compile("(?i).*(host|server|machine|node|ip_?addr).*"),
			"rawColumn", Pattern.compile("(?i).*(raw|log_?line|line_?text|entry).*"),
			"idColumn", Pattern.compile("(?i)(id|row_?id|log_?id|seq|sequence|.*_seq|.*_id|rownum)"));
	private static final Pattern TRACE = Pattern.compile("(?i)[0-9a-f]{8,}(:\\d+)?|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|(?=.*\\d)(?=.*[a-z])[a-z0-9_.:-]{10,64}");
	private static final Pattern HOST = Pattern.compile("(?i)(\\d{1,3}(\\.\\d{1,3}){3}|(?=.*[\\d.])[a-z][a-z0-9-]{1,40}(\\.[a-z0-9-]+)*)");
	private static final Set<String> LEVEL_WORDS = Set.of("TRACE", "DEBUG", "INFO", "NOTICE", "WARN", "WARNING", "ERROR", "ERR", "FATAL",
			"CRITICAL", "SEVERE", "ALERT", "EMERG", "DBG", "INF", "WRN", "FTL", "INFORMATION", "VERBOSE");
	private static final Set<String> TOKEN_ROLES = Set.of("traceColumn", "hostColumn", "serviceColumn");
	private static final Set<LogFormat> UNSTRUCTURED = Set.of(LogFormat.GENERIC, LogFormat.DATABASE, LogFormat.SPREADSHEET);

	private final UniversalLogParser parser;

	public ColumnDetector(UniversalLogParser parser) {
		this.parser = parser;
	}

	/** What was found: column per role, how sure (0-100) and why, plus how much the whole thing looks like logs. */
	public record Detection(Map<String, String> mapping, Map<String, Integer> confidence, Map<String, String> reasons, int logLikeness) {
	}

	public Detection detect(List<String> names, List<List<Object>> rows) {
		int cols = names.size();
		List<Map<String, Double>> scores = new ArrayList<>();
		List<Map<String, String>> why = new ArrayList<>();
		for (int c = 0; c < cols; c++) {
			List<Object> values = new ArrayList<>();
			for (List<Object> r : rows) {
				Object v = c < r.size() ? r.get(c) : null;
				if (v != null && !(v instanceof String s && s.isBlank())) values.add(v);
			}
			Map<String, String> w = new LinkedHashMap<>();
			Map<String, Double> content = scoreColumn(values, rows.size(), w);
			Map<String, Double> s = new LinkedHashMap<>();
			for (String role : ROLES) {
				double cs = content.getOrDefault(role, 0.0);
				boolean hinted = NAME_HINTS.get(role).matcher(names.get(c)).matches();
				double v = cs > .2 ? Math.min(1, cs * .85 + (hinted ? .15 : 0)) : hinted ? .22 : cs;
				// short codes with a telling name (SARM_ID = "abc:0", HOST = "app1") are trusted on the name
				if (hinted && content.containsKey("_token") && TOKEN_ROLES.contains(role)) {
					v = Math.max(v, .6);
					w.putIfAbsent(role, "short codes in a column named " + names.get(c));
				}
				s.put(role, v);
			}
			scores.add(s);
			why.add(w);
		}
		Map<String, String> mapping = new LinkedHashMap<>();
		Map<String, Integer> confidence = new LinkedHashMap<>();
		Map<String, String> reasons = new LinkedHashMap<>();
		Set<Integer> used = new HashSet<>();
		for (String role : ROLES) {
			if (role.equals("messageColumn") && mapping.containsKey("rawColumn") && bestScore(scores, used, role) < .6) continue;
			int best = -1;
			double bestScore = 0;
			for (int c = 0; c < cols; c++) {
				if (used.contains(c)) continue;
				double v = scores.get(c).get(role);
				if (v > bestScore) {
					bestScore = v;
					best = c;
				}
			}
			if (best >= 0 && bestScore >= THRESHOLD.get(role)) {
				used.add(best);
				mapping.put(role, names.get(best));
				confidence.put(role, (int) Math.round(bestScore * 100));
				reasons.put(role, why.get(best).getOrDefault(role, "matched by column name"));
			}
		}
		double ts = confidence.getOrDefault("tsColumn", 0) / 100.0;
		double text = Math.max(confidence.getOrDefault("messageColumn", 0), confidence.getOrDefault("rawColumn", 0)) / 100.0;
		double level = confidence.getOrDefault("levelColumn", 0) / 100.0;
		double raw = confidence.getOrDefault("rawColumn", 0) / 100.0;
		double l = ts * .4 + Math.max(text, raw) * .35 + (raw > 0 ? Math.max(level, .7) : level) * .25;
		if (ts == 0 && raw == 0) l *= .3; // a log without times is rarely a log (customer lists, lookup tables)
		l *= Math.min(1, .5 + rows.size() / 40.0); // a handful of rows is more likely reference data
		int likeness = rows.isEmpty() ? 0 : (int) Math.round(l * 100);
		return new Detection(mapping, confidence, reasons, likeness);
	}

	private static double bestScore(List<Map<String, Double>> scores, Set<Integer> used, String role) {
		double b = 0;
		for (int c = 0; c < scores.size(); c++) if (!used.contains(c)) b = Math.max(b, scores.get(c).get(role));
		return b;
	}

	/** Scores one column for every role from its values; fills {@code why} with a human explanation per role. */
	Map<String, Double> scoreColumn(List<Object> values, int rowCount, Map<String, String> why) {
		Map<String, Double> s = new LinkedHashMap<>();
		int n = values.size();
		if (n == 0) return s;
		int ts = 0, levels = 0, sev = 0, raw = 0, trace = 0, host = 0, ints = 0, spaces = 0, strings = 0;
		long lenSum = 0;
		Set<String> distinct = new HashSet<>();
		Set<String> levelSamples = new HashSet<>();
		boolean ascending = true, descending = true;
		Long prev = null;
		String rawFormat = null;
		for (Object v : values) {
			String str = v.toString().strip();
			distinct.add(str);
			if (Timestamps.parse(v) != null) ts++;
			if (v instanceof Number num && num.doubleValue() == Math.rint(num.doubleValue())) {
				ints++;
				long l = num.longValue();
				if (prev != null) {
					if (l <= prev) ascending = false;
					if (l >= prev) descending = false;
				}
				prev = l;
			} else if (str.matches("-?\\d{1,18}")) {
				ints++;
				long l = Long.parseLong(str);
				if (prev != null) {
					if (l <= prev) ascending = false;
					if (l >= prev) descending = false;
				}
				prev = l;
			}
			if (str.matches("[0-5]")) sev++;
			if (!(v instanceof String)) continue;
			strings++;
			lenSum += str.length();
			if (str.contains(" ")) spaces++;
			String up = str.toUpperCase(Locale.ROOT);
			if (LEVEL_WORDS.contains(up)) { levels++; levelSamples.add(up); }
			if (str.length() <= 64 && !str.contains(" ") && TRACE.matcher(str).matches()) trace++;
			if (str.length() <= 64 && !str.contains(" ") && !str.contains("_") && HOST.matcher(str).matches() && !str.matches("\\d+")) host++;
			if (str.length() > 20 && str.length() < 20000) {
				ParsedLog p = parser.parseLine(str.split("\\r?\\n", 2)[0], null).orElse(null);
				if (p != null && !UNSTRUCTURED.contains(p.getFormat())) {
					raw++;
					rawFormat = p.getFormat().getLabel();
				}
			}
		}
		double d = (double) distinct.size() / n;
		if (strings == n && spaces == 0 && ts == 0) s.put("_token", 1.0);
		double avgLen = strings == 0 ? 0 : (double) lenSum / strings;
		double spaceRatio = strings == 0 ? 0 : (double) spaces / strings;

		double tsR = (double) ts / n;
		if (tsR > 0 && !(ints == n && ts < n)) {
			s.put("tsColumn", tsR);
			why.put("tsColumn", pct(tsR) + " of sampled values are dates/times");
		}
		double rawR = (double) raw / n;
		if (rawR > 0) {
			s.put("rawColumn", rawR);
			why.put("rawColumn", pct(rawR) + " read as complete log lines (" + rawFormat + ")");
		}
		boolean idLike = ints == n && (ascending || descending) && distinct.size() == n && n >= 3;
		if (distinct.size() <= 10 && !(idLike && levels == 0)) {
			double lv = Math.max((double) levels / n, distinct.size() <= 6 ? (double) sev / n * .9 : 0);
			if (lv > 0) {
				s.put("levelColumn", lv);
				why.put("levelColumn", levels >= sev ? "values like " + String.join(", ", levelSamples.stream().limit(4).toList())
						: "numeric severities 0-5 (Siebel style)");
			}
		}
		if (strings > 0 && rawR < .5) {
			double msg = (spaceRatio * .5 + Math.min(1, avgLen / 60) * .3 + Math.min(1, d * 1.2) * .2) * ((double) strings / n);
			if (tsR > .5) msg *= .2;
			s.put("messageColumn", msg);
			why.put("messageColumn", "free text, about " + Math.round(avgLen) + " characters, " + pct(d) + " distinct");
		}
		double traceR = (double) trace / n;
		if (traceR > 0 && tsR < .5) {
			s.put("traceColumn", traceR * (d > .3 ? 1 : .4));
			why.put("traceColumn", "identifier-like values, e.g. " + sample(values));
		}
		if (idLike && tsR < .5) {
			s.put("idColumn", .95);
			why.put("idColumn", "unique numbers that increase row by row");
		}
		double hostR = (double) host / n;
		if (hostR > 0 && tsR < .5) {
			s.put("hostColumn", hostR * (d <= .5 || distinct.size() <= 20 ? 1 : .5));
			why.put("hostColumn", "host names / IP addresses, e.g. " + sample(values));
		}
		if (strings == n && avgLen >= 2 && avgLen <= 40 && spaceRatio <= .2 && tsR < .3 && rawR < .3) {
			boolean repeated = distinct.size() >= 1 && (distinct.size() <= Math.max(3, n * .35) || distinct.size() <= 30);
			if (repeated && levels < n / 2) {
				s.put("serviceColumn", .75 + (distinct.size() > 1 ? .1 : 0));
				why.put("serviceColumn", distinct.size() + " short names repeated across rows, e.g. " + sample(values));
			}
		}
		return s;
	}

	private static String pct(double v) {
		return Math.round(v * 100) + "%";
	}

	private static String sample(List<Object> values) {
		String v = String.valueOf(values.get(0));
		return v.length() > 32 ? v.substring(0, 31) + "…" : v;
	}
}
