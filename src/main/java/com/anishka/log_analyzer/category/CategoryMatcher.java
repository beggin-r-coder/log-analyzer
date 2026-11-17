package com.anishka.log_analyzer.category;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import org.springframework.data.jpa.domain.Specification;

import com.anishka.log_analyzer.entity.LogRecord;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;

/**
 * The rules of a custom category, compiled twice: as a Java predicate (fast single pass over many events for the
 * charts) and as a database query (paging through the matching events in the log explorer, counting for alerts).
 */
public final class CategoryMatcher {

	/** One condition: {@code field} (message, service, level, host, traceId, errorCategory, logger, any) {@code op} value. */
	public record Rule(String field, String op, String value, Boolean caseSensitive) {
		boolean exact() {
			return Boolean.TRUE.equals(caseSensitive);
		}
	}

	/** The fields of a log event a rule can look at. */
	public record Row(String level, String service, String host, String traceId, String errorCategory, String logger, String message) {
		String get(String field) {
			return switch (field) {
			case "service" -> service;
			case "level" -> level;
			case "host" -> host;
			case "traceId" -> traceId;
			case "errorCategory" -> errorCategory;
			case "logger" -> logger;
			default -> message;
			};
		}
	}

	public static final List<String> FIELDS = List.of("message", "service", "level", "host", "traceId", "errorCategory", "logger", "any");
	public static final List<String> OPS = List.of("contains", "notContains", "equals", "startsWith", "endsWith", "wildcard", "regex");
	private static final List<String> ANY_FIELDS = List.of("message", "service", "host", "traceId", "errorCategory", "logger");

	private final boolean all;
	private final Set<String> levels;
	private final List<Rule> rules;
	private final List<Predicate<Row>> tests = new ArrayList<>();

	public CategoryMatcher(List<Rule> rules, boolean all, String levels) {
		this.all = all;
		this.rules = rules.stream().filter(r -> r.value() != null && !r.value().isEmpty()).toList();
		this.levels = levels == null || levels.isBlank() ? Set.of()
				: Arrays.stream(levels.split(",")).map(s -> s.trim().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
		for (Rule r : this.rules) tests.add(compile(r));
	}

	/** Throws IllegalArgumentException with a readable message when a rule is not usable. */
	public static void validate(List<Rule> rules) {
		if (rules.stream().noneMatch(r -> r.value() != null && !r.value().isEmpty())) {
			throw new IllegalArgumentException("Add at least one rule, e.g. message contains \"Record not found\"");
		}
		for (Rule r : rules) {
			if (r.value() == null || r.value().isEmpty()) continue;
			if (!FIELDS.contains(field(r))) throw new IllegalArgumentException("Unknown field: " + r.field());
			if (!OPS.contains(op(r))) throw new IllegalArgumentException("Unknown condition: " + r.op());
			if ("regex".equals(op(r))) {
				try {
					Pattern.compile(r.value());
				} catch (PatternSyntaxException e) {
					throw new IllegalArgumentException("The pattern \"" + r.value() + "\" is not valid: " + e.getDescription());
				}
			}
		}
	}

	public boolean test(Row row) {
		if (!levels.isEmpty() && (row.level() == null || !levels.contains(row.level()))) return false;
		if (tests.isEmpty()) return false;
		if (all) {
			for (Predicate<Row> t : tests) if (!t.test(row)) return false;
			return true;
		}
		for (Predicate<Row> t : tests) if (t.test(row)) return true;
		return false;
	}

	private static String field(Rule r) {
		return r.field() == null || r.field().isBlank() ? "message" : r.field();
	}

	private static String op(Rule r) {
		return r.op() == null || r.op().isBlank() ? "contains" : r.op();
	}

	private static Predicate<Row> compile(Rule r) {
		String field = field(r), op = op(r);
		Predicate<String> p = text(op, r.value(), r.exact());
		if ("any".equals(field)) {
			if ("notContains".equals(op)) { // none of the fields contains it
				Predicate<String> has = text("contains", r.value(), r.exact());
				return row -> ANY_FIELDS.stream().noneMatch(f -> has.test(nz(row.get(f))));
			}
			return row -> ANY_FIELDS.stream().anyMatch(f -> p.test(nz(row.get(f))));
		}
		return row -> p.test(nz(row.get(field)));
	}

	private static Predicate<String> text(String op, String value, boolean exact) {
		String v = exact ? value : value.toLowerCase(Locale.ROOT);
		return switch (op) {
		case "notContains" -> s -> !norm(s, exact).contains(v);
		case "equals" -> s -> norm(s, exact).equals(v);
		case "startsWith" -> s -> norm(s, exact).startsWith(v);
		case "endsWith" -> s -> norm(s, exact).endsWith(v);
		case "wildcard" -> {
			Pattern pt = Pattern.compile(wildcardRegex(value), exact ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
			yield s -> pt.matcher(s).find();
		}
		case "regex" -> {
			Pattern pt = Pattern.compile(value, exact ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
			yield s -> pt.matcher(s).find();
		}
		default -> s -> norm(s, exact).contains(v);
		};
	}

	/** {@code *} any text, {@code ?} one character; matched anywhere in the field, like "contains". */
	static String wildcardRegex(String w) {
		StringBuilder sb = new StringBuilder();
		for (char c : w.toCharArray()) {
			if (c == '*') sb.append(".*");
			else if (c == '?') sb.append('.');
			else sb.append(Pattern.quote(String.valueOf(c)));
		}
		return sb.toString();
	}

	private static String norm(String s, boolean exact) {
		return exact ? s : s.toLowerCase(Locale.ROOT);
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	// ------------------------------------------------------------------ as a database query

	public Specification<LogRecord> specification() {
		return (root, query, cb) -> {
			List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();
			for (Rule r : rules) ps.add(predicate(root, cb, r));
			jakarta.persistence.criteria.Predicate rulesPart = ps.isEmpty() ? cb.disjunction()
					: all ? cb.and(ps.toArray(jakarta.persistence.criteria.Predicate[]::new))
							: cb.or(ps.toArray(jakarta.persistence.criteria.Predicate[]::new));
			return levels.isEmpty() ? rulesPart : cb.and(root.get("level").in(levels), rulesPart);
		};
	}

	private static jakarta.persistence.criteria.Predicate predicate(Root<LogRecord> root, CriteriaBuilder cb, Rule r) {
		String field = field(r), op = op(r);
		if ("any".equals(field)) {
			if ("notContains".equals(op)) {
				return cb.and(ANY_FIELDS.stream().map(f -> one(root, cb, f, "notContains", r)).toArray(jakarta.persistence.criteria.Predicate[]::new));
			}
			return cb.or(ANY_FIELDS.stream().map(f -> one(root, cb, f, op, r)).toArray(jakarta.persistence.criteria.Predicate[]::new));
		}
		return one(root, cb, field, op, r);
	}

	private static jakarta.persistence.criteria.Predicate one(Root<LogRecord> root, CriteriaBuilder cb, String field, String op, Rule r) {
		Expression<String> col = cb.coalesce(root.<String>get(field), "");
		boolean exact = r.exact();
		Expression<String> x = exact ? col : cb.lower(col);
		String v = exact ? r.value() : r.value().toLowerCase(Locale.ROOT);
		return switch (op) {
		case "notContains" -> cb.notLike(x, "%" + like(v) + "%", '\\');
		case "equals" -> cb.equal(x, v);
		case "startsWith" -> cb.like(x, like(v) + "%", '\\');
		case "endsWith" -> cb.like(x, "%" + like(v), '\\');
		case "wildcard" -> cb.like(x, "%" + like(v).replace("*", "%").replace("?", "_") + "%", '\\');
		case "regex" -> cb.isTrue(cb.function("REGEXP_LIKE", Boolean.class, col, cb.literal(r.value()), cb.literal(exact ? "c" : "i")));
		default -> cb.like(x, "%" + like(v) + "%", '\\');
		};
	}

	private static String like(String v) {
		return v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
