package com.anishka.log_analyzer.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.anishka.log_analyzer.entity.LogRecord;

/**
 * Lightweight NLP for log messages, in plain Java:
 * <ul>
 * <li>template mining: variable parts (ids, numbers, amounts, IPs...) are
 * masked so recurring errors collapse into one pattern;</li>
 * <li>entity extraction: transaction ids, error codes, exceptions, etc.;</li>
 * <li>TF-IDF keywords and cosine similarity between messages.</li>
 * </ul>
 */
@Service
public class LogNlpService {

	// ---------- template masking (order matters: most specific first) ----------
	private static final List<Object[]> MASKS = List.of(
			new Object[] { Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"), "<UUID>" },
			new Object[] { Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "<EMAIL>" },
			new Object[] { Pattern.compile("\\b[a-z]\\*{2,}\\d*@[a-z]+\\b"), "<VPA>" },
			new Object[] { Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b"), "<IP>" },
			new Object[] { Pattern.compile("\\b[0-9]?X{6,}\\d{4}\\b"), "<MASKED>" },
			new Object[] { Pattern.compile("\\b(?:INR|USD|EUR|GBP)\\s?[\\d,]+(?:\\.\\d+)?\\b"), "<AMOUNT>" },
			// identifiers, but not protocol names that carry meaning (ISO8583)
			new Object[] { Pattern.compile("\\b(?!ISO8583\\b)[A-Z]{2,6}\\d{4,}\\b"), "<ID>" },
			// Siebel row ids such as 1-ABC123 (but never SBL-DAT-00498 error codes, which are kept as signal)
			new Object[] { Pattern.compile("\\b\\d{1,3}-(?=[A-Z0-9]*[A-Z])[A-Z0-9]{3,9}\\b"), "<ROWID>" },
			new Object[] { Pattern.compile("\\b(?=[0-9a-f]*\\d)(?=[0-9a-f]*[a-f])[0-9a-f]{8,}\\b"), "<HEX>" },
			// numbers, but keep HTTP status codes (502 vs 503 matters)
			new Object[] { Pattern.compile("(?<!HTTP )(?<!SBL-[A-Z]{3}-)(?<!ORA-)\\b\\d+(?:[.,]\\d+)*(ms|s|m|h|%)?\\b"), "<NUM>$1" });

	// ---------- entity patterns ----------
	private static final Map<String, Pattern> ENTITIES = new LinkedHashMap<>();
	static {
		ENTITIES.put("exception", Pattern.compile("\\b(?:[a-z]+\\.)*([A-Z][A-Za-z]+(?:Exception|Error))\\b"));
		ENTITIES.put("errorCode", Pattern.compile("\\b(SBL-[A-Z]{3}-\\d{5}|ORA-\\d{5}|HTTP \\d{3}|RespCode \\w+|AH\\d{5}|U\\d{2}\\b|code \\d{2,3}|MT\\d{3}|ISO ?8583)"));
		ENTITIES.put("transaction", Pattern.compile("\\b((?:TXN|NOVA|ORD|RRN)\\d{8,})\\b"));
		ENTITIES.put("reference", Pattern.compile("\\b((?:LA|APP|CLM|POL|FX|FD|SI|VK|MID|CIF|ATM)\\d{4,})\\b"));
		ENTITIES.put("account", Pattern.compile("\\b(X{6,}\\d{4})\\b"));
		ENTITIES.put("card", Pattern.compile("\\b(\\dX{8,}\\d{4})\\b"));
		ENTITIES.put("vpa", Pattern.compile("\\b([a-z]\\*{2,}\\d*@[a-z]+)\\b"));
		ENTITIES.put("duration", Pattern.compile("\\b(\\d+ ?ms)\\b"));
		ENTITIES.put("amount", Pattern.compile("\\b((?:INR|USD) ?[\\d,]+(?:\\.\\d+)?)\\b"));
		ENTITIES.put("siebelObject", Pattern.compile("(?:Business Service|BusComp|Business Component|Workflow Process|Web Service|Integration Object|Applet|View) '([^']{2,80})'"));
		ENTITIES.put("rowId", Pattern.compile("\\b(\\d{1,3}-(?=[A-Z0-9]*[A-Z])[A-Z0-9]{3,9})\\b"));
		ENTITIES.put("table", Pattern.compile("\\b([A-Z][A-Z0-9]*_[A-Z0-9_]{2,})\\b"));
	}

	private static final Set<String> STOP = Set.of("the", "a", "an", "and", "or", "for", "to", "of", "in", "on", "at",
			"by", "is", "was", "be", "with", "from", "after", "as", "it", "this", "that", "not", "no", "via", "id",
			"num", "uuid", "hex", "masked", "amount", "ip", "vpa", "email", "ms", "while", "during", "into", "than");
	private static final Pattern WORD = Pattern.compile("[a-z][a-z0-9]+");

	// =====================================================================
	// TEMPLATES & CLUSTERS
	// =====================================================================

	/** "NPCI switch timeout for NOVA0392 after 30000ms" -> "NPCI switch timeout for <ID> after <NUM>ms" */
	public String template(String message) {
		if (message == null) {
			return "";
		}
		String firstLine = message.lines().findFirst().orElse("");
		String t = firstLine;
		for (Object[] mask : MASKS) {
			t = ((Pattern) mask[0]).matcher(t).replaceAll((String) mask[1]);
		}
		return t.replaceAll("\\s+", " ").trim();
	}

	public record Cluster(int rank, String template, long count, Set<String> services, String category, String level,
			long firstSeen, long lastSeen, Long sampleId, String sample) {
	}

	/** Groups records by template, biggest first. */
	public List<Cluster> clusters(List<LogRecord> records, int limit) {
		Map<String, List<LogRecord>> groups = new LinkedHashMap<>();
		for (LogRecord r : records) {
			groups.computeIfAbsent((r.getService() == null ? "" : r.getService()) + "|" + template(r.getMessage()),
					k -> new ArrayList<>()).add(r);
		}
		List<List<LogRecord>> sorted = new ArrayList<>(groups.values());
		sorted.sort(Comparator.comparingInt((List<LogRecord> g) -> g.size()).reversed());
		// merge clusters that share a template across services
		Map<String, Cluster> byTemplate = new LinkedHashMap<>();
		for (List<LogRecord> g : sorted) {
			LogRecord first = g.get(0);
			String tpl = template(first.getMessage());
			Cluster existing = byTemplate.get(tpl);
			long min = g.stream().mapToLong(r -> epoch(r)).min().orElse(0);
			long max = g.stream().mapToLong(r -> epoch(r)).max().orElse(0);
			LogRecord latest = g.stream().max(Comparator.comparing(LogRecord::getTimestamp)).orElse(first);
			if (existing == null) {
				byTemplate.put(tpl, new Cluster(0, tpl, g.size(), new TreeSet<>(Set.of(nz(first.getService()))),
						first.getErrorCategory(), first.getLevel(), min, max, latest.getId(), latest.getMessage()));
			} else {
				Set<String> svcs = new TreeSet<>(existing.services());
				svcs.add(nz(first.getService()));
				byTemplate.put(tpl, new Cluster(0, tpl, existing.count() + g.size(), svcs, existing.category(),
						existing.level(), Math.min(existing.firstSeen(), min), Math.max(existing.lastSeen(), max),
						existing.sampleId(), existing.sample()));
			}
		}
		List<Cluster> out = new ArrayList<>(byTemplate.values());
		out.sort(Comparator.comparingLong(Cluster::count).reversed());
		List<Cluster> ranked = new ArrayList<>();
		for (int i = 0; i < Math.min(limit, out.size()); i++) {
			Cluster c = out.get(i);
			ranked.add(new Cluster(i + 1, c.template(), c.count(), c.services(), c.category(), c.level(),
					c.firstSeen(), c.lastSeen(), c.sampleId(), c.sample()));
		}
		return ranked;
	}

	// =====================================================================
	// ENTITIES
	// =====================================================================

	public Map<String, List<String>> entities(String text) {
		Map<String, List<String>> out = new LinkedHashMap<>();
		if (text == null) {
			return out;
		}
		ENTITIES.forEach((type, p) -> {
			Set<String> found = new LinkedHashSet<>();
			Matcher m = p.matcher(text);
			while (m.find() && found.size() < 8) {
				found.add(m.group(1));
			}
			if (!found.isEmpty()) {
				out.put(type, new ArrayList<>(found));
			}
		});
		return out;
	}

	// =====================================================================
	// TF-IDF KEYWORDS & SIMILARITY
	// =====================================================================

	public List<String> tokens(String message) {
		List<String> out = new ArrayList<>();
		Matcher m = WORD.matcher(template(message).toLowerCase(Locale.ROOT));
		while (m.find()) {
			String w = m.group();
			// identifiers (anything with a digit) carry no meaning for similarity
			if (w.length() > 2 && !STOP.contains(w) && w.chars().noneMatch(Character::isDigit)) {
				out.add(w);
			}
		}
		return out;
	}

	/** Inverse document frequency over a corpus of messages. */
	public Map<String, Double> idf(List<String> corpus) {
		Map<String, Integer> df = new HashMap<>();
		for (String doc : corpus) {
			for (String t : new HashSet<>(tokens(doc))) {
				df.merge(t, 1, Integer::sum);
			}
		}
		Map<String, Double> idf = new HashMap<>();
		int n = Math.max(1, corpus.size());
		df.forEach((t, d) -> idf.put(t, Math.log(1.0 + n / (double) d)));
		return idf;
	}

	public Map<String, Double> vector(String message, Map<String, Double> idf) {
		Map<String, Double> tf = new HashMap<>();
		for (String t : tokens(message)) {
			tf.merge(t, 1.0, Double::sum);
		}
		Map<String, Double> v = new HashMap<>();
		tf.forEach((t, f) -> v.put(t, f * idf.getOrDefault(t, Math.log(2.0))));
		return v;
	}

	public static double cosine(Map<String, Double> a, Map<String, Double> b) {
		if (a.isEmpty() || b.isEmpty()) {
			return 0;
		}
		double dot = 0;
		for (Map.Entry<String, Double> e : a.entrySet()) {
			Double o = b.get(e.getKey());
			if (o != null) dot += e.getValue() * o;
		}
		double na = Math.sqrt(a.values().stream().mapToDouble(x -> x * x).sum());
		double nb = Math.sqrt(b.values().stream().mapToDouble(x -> x * x).sum());
		return na == 0 || nb == 0 ? 0 : dot / (na * nb);
	}

	/** Highest-weighted terms across the corpus (summed TF-IDF). */
	public List<String> keywords(List<String> corpus, int n) {
		Map<String, Double> idf = idf(corpus);
		Map<String, Double> score = new HashMap<>();
		for (String doc : corpus) {
			vector(doc, idf).forEach((t, w) -> score.merge(t, w, Double::sum));
		}
		return score.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(n)
				.map(Map.Entry::getKey).toList();
	}

	public record Similar(LogRecord record, double score) {
	}

	/** Most similar records to the target (different ids), above a minimum score. */
	public List<Similar> similar(LogRecord target, List<LogRecord> candidates, int n, double minScore) {
		List<String> corpus = new ArrayList<>();
		candidates.forEach(c -> corpus.add(c.getMessage()));
		corpus.add(target.getMessage());
		Map<String, Double> idf = idf(corpus);
		Map<String, Double> tv = vector(target.getMessage(), idf);
		String targetTemplate = template(target.getMessage());
		List<Similar> scored = new ArrayList<>();
		for (LogRecord c : candidates) {
			if (c.getId().equals(target.getId())) continue;
			double s = cosine(tv, vector(c.getMessage(), idf));
			if (s >= minScore) scored.add(new Similar(c, s));
		}
		scored.sort(Comparator.comparingDouble(Similar::score).reversed()
				.thenComparing((Similar x) -> x.record().getTimestamp(), Comparator.reverseOrder()));
		// show variety: at most two per template, and prefer other patterns than the target's own
		List<Similar> out = new ArrayList<>();
		Map<String, Integer> perTemplate = new HashMap<>();
		for (Similar s : scored) {
			String tpl = template(s.record().getMessage());
			int used = perTemplate.getOrDefault(tpl, 0);
			if (used >= (tpl.equals(targetTemplate) ? 2 : 1)) continue;
			perTemplate.put(tpl, used + 1);
			out.add(s);
			if (out.size() >= n) break;
		}
		return out;
	}

	private static long epoch(LogRecord r) {
		return r.getTimestamp().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	private static String nz(String s) {
		return s == null ? "unknown" : s;
	}
}
