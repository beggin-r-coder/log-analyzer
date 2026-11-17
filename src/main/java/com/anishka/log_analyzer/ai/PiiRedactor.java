package com.anishka.log_analyzer.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Removes personal and secret data from log text before it leaves the bank's
 * network for an external LLM: emails, card numbers (Luhn-checked), Aadhaar,
 * PAN, phone numbers, client IPs (last two octets), bearer/JWT tokens and
 * password/secret values.
 */
@Component
public class PiiRedactor {

	public record Result(String text, Map<String, Integer> counts) {
		public int total() {
			return counts.values().stream().mapToInt(Integer::intValue).sum();
		}
	}

	private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
	private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
	private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]{12,}");
	private static final Pattern SECRET_KV = Pattern.compile("(?i)\\b(password|passwd|pwd|secret|api[_-]?key|token|pin)(\\s*[=:]\\s*)(\"[^\"]*\"|\\S+)");
	private static final Pattern CARD = Pattern.compile("(?<![\\w-])(?:\\d[ -]?){12,18}\\d(?![\\w-])");
	private static final Pattern AADHAAR = Pattern.compile("(?<!\\d)\\d{4}[ -]\\d{4}[ -]\\d{4}(?!\\d)");
	private static final Pattern INDIAN_PAN = Pattern.compile("\\b[A-Z]{5}\\d{4}[A-Z]\\b");
	private static final Pattern PHONE = Pattern.compile("(?<![\\w+])(?:\\+91[ -]?)?[6-9]\\d{9}(?!\\d)");
	private static final Pattern IPV4 = Pattern.compile("\\b((?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d))\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");

	public Result redact(String input) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		if (input == null || input.isEmpty()) {
			return new Result(input == null ? "" : input, counts);
		}
		String s = input;
		s = replace(s, JWT, "[TOKEN]", "token", counts);
		s = replaceKeepGroup(s, BEARER, 1, " [TOKEN]", "token", counts);
		s = replaceSecretValues(s, counts);
		s = replace(s, EMAIL, "[EMAIL]", "email", counts);
		s = replaceCards(s, counts);
		s = replace(s, AADHAAR, "[AADHAAR]", "aadhaar", counts);
		s = replace(s, INDIAN_PAN, "[PAN]", "pan", counts);
		s = replace(s, PHONE, "[PHONE]", "phone", counts);
		s = replaceKeepGroup(s, IPV4, 1, ".x.x", "ip", counts);
		return new Result(s, counts);
	}

	private static String replace(String s, Pattern p, String with, String type, Map<String, Integer> counts) {
		Matcher m = p.matcher(s);
		StringBuilder sb = new StringBuilder();
		int n = 0;
		while (m.find()) {
			m.appendReplacement(sb, Matcher.quoteReplacement(with));
			n++;
		}
		m.appendTail(sb);
		if (n > 0) counts.merge(type, n, Integer::sum);
		return sb.toString();
	}

	private static String replaceKeepGroup(String s, Pattern p, int group, String suffix, String type, Map<String, Integer> counts) {
		Matcher m = p.matcher(s);
		StringBuilder sb = new StringBuilder();
		int n = 0;
		while (m.find()) {
			m.appendReplacement(sb, Matcher.quoteReplacement(m.group(group) + suffix));
			n++;
		}
		m.appendTail(sb);
		if (n > 0) counts.merge(type, n, Integer::sum);
		return sb.toString();
	}

	private static String replaceSecretValues(String s, Map<String, Integer> counts) {
		Matcher m = SECRET_KV.matcher(s);
		StringBuilder sb = new StringBuilder();
		int n = 0;
		while (m.find()) {
			if (m.group(3).startsWith("[")) { // already redacted
				m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
				continue;
			}
			m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2) + "[SECRET]"));
			n++;
		}
		m.appendTail(sb);
		if (n > 0) counts.merge("secret", n, Integer::sum);
		return sb.toString();
	}

	/** Only digit runs that pass the Luhn check count as card numbers, so IDs and RRNs survive. */
	private static String replaceCards(String s, Map<String, Integer> counts) {
		Matcher m = CARD.matcher(s);
		StringBuilder sb = new StringBuilder();
		int n = 0;
		while (m.find()) {
			String digits = m.group().replaceAll("[ -]", "");
			if (digits.length() >= 13 && digits.length() <= 19 && luhn(digits)) {
				m.appendReplacement(sb, Matcher.quoteReplacement("[CARD]"));
				n++;
			} else {
				m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
			}
		}
		m.appendTail(sb);
		if (n > 0) counts.merge("card", n, Integer::sum);
		return sb.toString();
	}

	static boolean luhn(String digits) {
		int sum = 0;
		boolean dbl = false;
		for (int i = digits.length() - 1; i >= 0; i--) {
			int d = digits.charAt(i) - '0';
			if (dbl) {
				d *= 2;
				if (d > 9) d -= 9;
			}
			sum += d;
			dbl = !dbl;
		}
		return sum % 10 == 0;
	}
}
