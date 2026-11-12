package com.anishka.log_analyzer.simulator;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.springframework.stereotype.Component;

import com.anishka.log_analyzer.parser.LogFormat;

import tools.jackson.databind.json.JsonMapper;

/**
 * Produces realistic log lines for a fictional bank ("NovaBank"). Every
 * service writes in its own native format, the way a real BFSI estate looks:
 * Spring Boot core banking, JSON UPI switch, syslog ATM switch, nginx
 * net-banking, and so on. All customer identifiers are masked.
 */
@Component
public class BfsiLogGenerator {

	/** A raw log event plus the service name to fall back on if the format has none. */
	public record GeneratedLog(String service, String raw) {
	}

	record Msg(String text, String logger, boolean stackTrace) {
		Msg(String text, String logger) {
			this(text, logger, false);
		}
	}

	record Profile(String name, LogFormat format, double weight, String hostPrefix, List<Msg> info, List<Msg> warn,
			List<Msg> error, List<Msg> fatal, List<Msg> incident) {
	}

	private static final DateTimeFormatter ISO_MILLIS_OFFSET = DateTimeFormatter
			.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
	private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
	private static final DateTimeFormatter LOG4J_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");
	private static final DateTimeFormatter PIPE_TS = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS");
	private static final DateTimeFormatter DMY_TS = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
	private static final DateTimeFormatter ACCESS_TS = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z",
			Locale.ENGLISH);
	private static final DateTimeFormatter SYSLOG_TS = DateTimeFormatter.ofPattern("MMM ppd HH:mm:ss", Locale.ENGLISH);

	private static final String[] NB_OK_PATHS = { "GET /netbanking/login", "POST /netbanking/login",
			"GET /api/v1/accounts/summary", "POST /api/v1/fund-transfer", "GET /api/v1/statements/download",
			"POST /api/v1/bill-pay", "POST /api/v1/beneficiary/add", "GET /api/v1/cards", "POST /api/v1/upi/collect",
			"GET /static/js/app.min.js" };
	private static final String[] USER_AGENTS = {
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36",
			"Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148",
			"Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36",
			"NovaBankApp/4.3.0 (Android 14)" };
	private static final String[] PAYMENT_METHODS = { "UPI", "CARD", "NETBANKING", "WALLET" };
	private static final String[] LOAN_TYPES = { "HOME_LOAN", "PERSONAL_LOAN", "AUTO_LOAN", "GOLD_LOAN",
			"EDUCATION_LOAN" };
	private static final String[] PSP = { "oknova", "ybl", "axl", "ibl", "paytm" };
	private static final String[] FX_PAIRS = { "USD/INR", "EUR/INR", "GBP/INR", "JPY/INR" };

	private final List<Profile> profiles = buildProfiles();
	private final double totalWeight = profiles.stream().mapToDouble(Profile::weight).sum();
	private final JsonMapper json = JsonMapper.builder().build();

	public List<String> serviceNames() {
		return profiles.stream().map(Profile::name).toList();
	}

	/** High-traffic services: an incident on one of these is clearly visible on the dashboard. */
	public List<String> busyServiceNames() {
		return profiles.stream().filter(p -> p.weight() >= 10).map(Profile::name).toList();
	}

	/**
	 * Generates one event.
	 *
	 * @param incidentService service currently suffering an incident (or null)
	 */
	public GeneratedLog next(LocalDateTime ts, Random rnd, String incidentService) {
		Profile p = pickProfile(rnd, incidentService);
		boolean inIncident = p.name().equals(incidentService);

		String level;
		Msg msg;
		double roll = rnd.nextDouble();
		if (inIncident && roll < 0.55) {
			level = "ERROR";
			msg = pick(p.incident(), rnd);
		} else if (roll < 0.0012 && !p.fatal().isEmpty()) {
			level = "FATAL";
			msg = pick(p.fatal(), rnd);
		} else if (roll < 0.0135) {
			level = "ERROR";
			msg = pick(p.error(), rnd);
		} else if (roll < 0.075) {
			level = "WARN";
			msg = pick(p.warn(), rnd);
		} else if (roll < 0.095) {
			level = "DEBUG";
			msg = pick(p.info(), rnd);
		} else {
			level = "INFO";
			msg = pick(p.info(), rnd);
		}
		String text = fill(msg.text(), rnd);
		String trace = hex(rnd, 16);
		String host = p.hostPrefix() + "-0" + (1 + rnd.nextInt(3));
		String raw = format(p, ts, level, text, msg.logger(), trace, host, rnd);
		if (msg.stackTrace()) {
			raw += stackTrace(msg.logger(), rnd);
		}
		return new GeneratedLog(p.name(), raw);
	}

	// =====================================================================
	// FORMATTERS – one per native format
	// =====================================================================

	private String format(Profile p, LocalDateTime ts, String level, String text, String logger, String trace,
			String host, Random rnd) {
		ZonedDateTime zts = ts.atZone(ZoneId.systemDefault());
		String ip = ip(rnd);
		return switch (p.format()) {
		case SPRING_BOOT -> String.format("%s %5s %d --- [%s] [%s] %-40s : %s traceId=%s", zts.format(ISO_MILLIS_OFFSET),
				level, 4200 + rnd.nextInt(80), p.name(), "nio-8080-exec-" + (1 + rnd.nextInt(20)), logger, text, trace);
		case BRACKETED -> String.format("%s [%s] [%s] [req-%s] [%s] %s", ts.format(ISO_MILLIS), level, p.name(),
				hex(rnd, 8), ip, text);
		case JSON -> toJson(p, zts, level, text, logger, trace, host, ip);
		case LOGFMT -> String.format("ts=%s level=%s service=%s host=%s trace_id=%s msg=\"%s\"",
				zts.withZoneSameInstant(ZoneOffset.UTC).format(ISO_MILLIS) + "Z", // UTC, as Go/Node services emit
				level.toLowerCase(Locale.ROOT), p.name(), host, trace, text.replace("\"", "\\\""));
		case ACCESS_LOG -> accessLog(zts, level, rnd, ip);
		case SYSLOG_RFC3164 -> String.format("%s %s %s[%d]: %s %s", ts.format(SYSLOG_TS), host, p.name(),
				2000 + rnd.nextInt(900), level, text);
		case LOG4J -> String.format("%s %-5s [http-nio-8443-exec-%d] %s - %s", ts.format(LOG4J_TS), level,
				1 + rnd.nextInt(16), logger, text);
		case PYTHON -> String.format("%s - %s - %s - %s", ts.format(LOG4J_TS), p.name(),
				"WARN".equals(level) ? "WARNING" : "FATAL".equals(level) ? "CRITICAL" : level, text);
		case SYSLOG_RFC5424 -> String.format("<%d>1 %s %s %s %d - - %s", 16 * 8 + syslogSeverity(level),
				zts.format(ISO_MILLIS_OFFSET), host, p.name(), 3300 + rnd.nextInt(50), text);
		case PIPE_DELIMITED -> String.format("%s | %s | %s | %s", ts.format(PIPE_TS), level, p.name(), text);
		default -> String.format("%s %s %s", ts.format(DMY_TS), shortLevel(level), text);
		};
	}

	private String toJson(Profile p, ZonedDateTime zts, String level, String text, String logger, String trace,
			String host, String ip) {
		Map<String, Object> m = new LinkedHashMap<>();
		if ("mobile-banking-api".equals(p.name())) {
			// nested ECS-style document, to show nested-field flattening
			m.put("@timestamp", zts.format(ISO_MILLIS_OFFSET));
			m.put("log", Map.of("level", level.toLowerCase(Locale.ROOT), "logger", logger));
			m.put("service", Map.of("name", p.name(), "version", "4.3.0"));
			m.put("host", Map.of("name", host));
			m.put("trace", Map.of("id", trace));
			m.put("client", Map.of("ip", ip));
			m.put("message", text);
		} else {
			m.put("timestamp", zts.format(ISO_MILLIS_OFFSET));
			m.put("level", level);
			m.put("service", p.name());
			m.put("host", host);
			m.put("logger", logger);
			m.put("traceId", trace);
			m.put("message", text);
		}
		return json.writeValueAsString(m);
	}

	private String accessLog(ZonedDateTime zts, String level, Random rnd, String ip) {
		String req;
		int status;
		switch (level) {
		case "ERROR", "FATAL" -> {
			req = pickStr(new String[] { "POST /api/v1/fund-transfer", "POST /api/v1/upi/collect",
					"GET /api/v1/accounts/summary", "POST /api/v1/bill-pay" }, rnd);
			status = new int[] { 500, 502, 503, 504 }[rnd.nextInt(4)];
		}
		case "WARN" -> {
			req = pickStr(new String[] { "POST /netbanking/login", "GET /api/v1/cards", "POST /api/v1/beneficiary/add",
					"GET /admin" }, rnd);
			status = new int[] { 401, 403, 404, 429 }[rnd.nextInt(4)];
		}
		default -> {
			req = pickStr(NB_OK_PATHS, rnd);
			status = rnd.nextInt(10) == 0 ? 302 : 200;
		}
		}
		return String.format("%s - - [%s] \"%s HTTP/1.1\" %d %d \"-\" \"%s\" rt=%.3f", ip, zts.format(ACCESS_TS), req,
				status, 200 + rnd.nextInt(18000), pickStr(USER_AGENTS, rnd),
				status >= 500 ? 2 + rnd.nextDouble() * 28 : rnd.nextDouble() * 0.8);
	}

	private String stackTrace(String logger, Random rnd) {
		String cls = logger.substring(logger.lastIndexOf('.') + 1);
		return "\n\tat " + logger + "." + "process(" + cls + ".java:" + (80 + rnd.nextInt(200)) + ")"
				+ "\n\tat com.novabank.common.web.TxnFilter.doFilter(TxnFilter.java:57)"
				+ "\n\tat org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885)"
				+ "\n\t... 42 more";
	}

	private static int syslogSeverity(String level) {
		return switch (level) {
		case "FATAL" -> 2;
		case "ERROR" -> 3;
		case "WARN" -> 4;
		case "DEBUG" -> 7;
		default -> 6;
		};
	}

	private static String shortLevel(String level) {
		return switch (level) {
		case "ERROR" -> "ERR";
		case "WARN" -> "WRN";
		case "DEBUG" -> "DBG";
		case "FATAL" -> "FTL";
		default -> "INF";
		};
	}

	// =====================================================================
	// PLACEHOLDERS
	// =====================================================================

	private String fill(String tpl, Random r) {
		StringBuilder sb = new StringBuilder();
		int i = 0;
		while (i < tpl.length()) {
			int open = tpl.indexOf('{', i);
			if (open < 0) {
				sb.append(tpl, i, tpl.length());
				break;
			}
			int close = tpl.indexOf('}', open);
			sb.append(tpl, i, open);
			sb.append(value(tpl.substring(open + 1, close), r));
			i = close + 1;
		}
		return sb.toString();
	}

	private String value(String key, Random r) {
		return switch (key) {
		// customers come from a finite book, so the same customer can fail more than once
		case "acct" -> "XXXXXX" + (1000 + r.nextInt(1800));
		case "card" -> (r.nextBoolean() ? "4" : "5") + "XXXXXXXXXXX" + (2000 + r.nextInt(1500));
		case "amt" -> String.format("%,.2f", amount(r));
		case "txn" -> "TXN" + digits(r, 12);
		case "upitxn" -> "NOVA" + digits(r, 14);
		case "rrn" -> digits(r, 12);
		case "vpa" -> (char) ('a' + r.nextInt(14)) + "****" + digits(r, 2) + "@" + pickStr(PSP, r);
		case "ms" -> String.valueOf(40 + r.nextInt(2400));
		case "slowms" -> String.valueOf(900 + r.nextInt(4000));
		case "pct" -> String.valueOf(70 + r.nextInt(29));
		case "lowpct" -> String.valueOf(5 + r.nextInt(12));
		case "num" -> digits(r, 7);
		case "cif" -> String.valueOf(104_200_000 + r.nextInt(2500) * 37);
		case "mid" -> digits(r, 6);
		case "order" -> "ORD" + digits(r, 10);
		case "ifsc" -> "NOVA000" + digits(r, 4);
		case "atm" -> "ATM" + digits(r, 5);
		case "n" -> String.valueOf(5 + r.nextInt(60));
		case "big" -> String.format("%,d", 1000 + r.nextInt(90000));
		case "score" -> String.valueOf(300 + r.nextInt(600));
		case "fraudscore" -> String.valueOf(r.nextInt(400));
		case "months" -> String.valueOf(new int[] { 12, 24, 36, 60 }[r.nextInt(4)]);
		case "method" -> pickStr(PAYMENT_METHODS, r);
		case "loantype" -> pickStr(LOAN_TYPES, r);
		case "pair" -> pickStr(FX_PAIRS, r);
		case "rate" -> String.format("%.4f", 83 + r.nextDouble() * 2);
		case "code" -> digits(r, 6);
		case "sess" -> hex(r, 12);
		case "ip" -> ip(r);
		default -> "{" + key + "}";
		};
	}

	private static double amount(Random r) {
		double x = r.nextDouble();
		if (x < 0.6) return 50 + r.nextDouble() * 5_000;
		if (x < 0.9) return 5_000 + r.nextDouble() * 95_000;
		return 100_000 + r.nextDouble() * 1_900_000;
	}

	private static String digits(Random r, int n) {
		StringBuilder sb = new StringBuilder(n);
		for (int i = 0; i < n; i++) sb.append((char) ('0' + r.nextInt(10)));
		return sb.toString();
	}

	private static String hex(Random r, int n) {
		StringBuilder sb = new StringBuilder(n);
		for (int i = 0; i < n; i++) sb.append("0123456789abcdef".charAt(r.nextInt(16)));
		return sb.toString();
	}

	private static String ip(Random r) {
		return r.nextInt(3) == 0 ? "10.20." + r.nextInt(255) + "." + (1 + r.nextInt(254))
				: (49 + r.nextInt(150)) + "." + r.nextInt(255) + "." + r.nextInt(255) + "." + (1 + r.nextInt(254));
	}

	private Profile pickProfile(Random rnd, String incidentService) {
		double boost = 0;
		Profile boosted = null;
		if (incidentService != null) {
			boosted = profiles.stream().filter(p -> p.name().equals(incidentService)).findFirst().orElse(null);
			boost = boosted == null ? 0 : totalWeight * 0.25; // retries make the failing service noisier
		}
		double x = rnd.nextDouble() * (totalWeight + boost);
		if (boosted != null && x >= totalWeight) {
			return boosted;
		}
		for (Profile p : profiles) {
			x -= p.weight();
			if (x < 0) return p;
		}
		return profiles.get(0);
	}

	private static <T> T pick(List<T> list, Random r) {
		return list.get(r.nextInt(list.size()));
	}

	private static String pickStr(String[] arr, Random r) {
		return arr[r.nextInt(arr.length)];
	}

	// =====================================================================
	// SERVICE CATALOGUE
	// =====================================================================

	private static List<Profile> buildProfiles() {
		String cbs = "com.novabank.core.";
		String loan = "com.novabank.los.";
		return List.of(
				new Profile("core-banking", LogFormat.SPRING_BOOT, 16, "cbs-app",
						List.of(new Msg("Fund transfer {txn} posted: debit acct {acct} credit acct {acct} amount INR {amt}", cbs + "ledger.PostingService"),
								new Msg("Balance enquiry served for acct {acct} in {ms}ms", cbs + "account.BalanceService"),
								new Msg("Standing instruction SI{num} executed for acct {acct}, amount INR {amt}", cbs + "si.StandingInstructionJob"),
								new Msg("Interest accrual processed {big} accounts for branch {ifsc}", cbs + "batch.InterestAccrualJob"),
								new Msg("Fixed deposit FD{num} opened for CIF{cif}, tenure {months} months", cbs + "deposit.FixedDepositService"),
								new Msg("Cheque {num} cleared for acct {acct}, amount INR {amt}", cbs + "clearing.ChequeClearingService")),
						List.of(new Msg("Slow query on ACCT_TXN_HISTORY took {slowms}ms (threshold 800ms)", cbs + "repo.TxnHistoryRepository"),
								new Msg("HikariPool-1 usage at {pct}% (active connections near max 50)", cbs + "config.PoolMonitor"),
								new Msg("Retrying posting for {txn}, attempt 2/3", cbs + "ledger.PostingService")),
						List.of(new Msg("Transaction rollback for {txn}: lien amount exceeds available balance on acct {acct}", cbs + "ledger.PostingService"),
								new Msg("Ledger posting failed for {txn}: OptimisticLockException on ACCT_BALANCE", cbs + "ledger.PostingService", true),
								new Msg("java.lang.NullPointerException: Cannot invoke \"Account.getBranch()\" because \"account\" is null", cbs + "account.AccountService", true)),
						List.of(new Msg("EOD reconciliation mismatch: debit/credit difference of INR {amt} in general ledger", cbs + "batch.EodReconciliationJob")),
						List.of(new Msg("HikariPool-1 - Connection is not available, request timed out after 30000ms", cbs + "ledger.PostingService", true),
								new Msg("Database deadlock detected on ACCT_BALANCE while posting {txn}", cbs + "ledger.PostingService"),
								new Msg("java.sql.SQLRecoverableException: ORA-17002 IO error, database connection reset", cbs + "repo.AccountRepository", true))),

				new Profile("payment-gateway", LogFormat.BRACKETED, 12, "pg-node",
						List.of(new Msg("Payment authorised for merchant MID{mid} order {order} amount INR {amt} via {method}", ""),
								new Msg("Refund {txn} initiated for order {order}, amount INR {amt}", ""),
								new Msg("Webhook delivered to merchant MID{mid} in {ms}ms", ""),
								new Msg("Settlement file generated for merchant MID{mid}: {n} transactions", "")),
						List.of(new Msg("Webhook delivery to merchant MID{mid} failed, retry 1/5", ""),
								new Msg("Merchant MID{mid} at {pct}% of daily rate quota", "")),
						List.of(new Msg("Payment failed for order {order}: acquirer declined with code 91 (issuer unavailable)", ""),
								new Msg("Duplicate transaction detected for order {order}, request rejected", ""),
								new Msg("API gateway circuit breaker OPEN for route /v2/payments", "")),
						List.of(),
						List.of(new Msg("Upstream acquirer returned HTTP 503 for order {order} after {slowms}ms", ""),
								new Msg("Payment authorisation failed for order {order}: acquirer connection timed out", ""))),

				new Profile("upi-switch", LogFormat.JSON, 18, "upi-sw",
						List.of(new Msg("UPI collect request {upitxn} from {vpa} amount INR {amt} SUCCESS", "com.novabank.upi.CollectHandler"),
								new Msg("UPI pay request {upitxn} completed, RRN {rrn}", "com.novabank.upi.PayHandler"),
								new Msg("VPA validation successful for {vpa}", "com.novabank.upi.VpaService"),
								new Msg("Mandate {upitxn} created for {vpa}, max amount INR {amt}", "com.novabank.upi.MandateService")),
						List.of(new Msg("NPCI response latency high: {slowms}ms for {upitxn}", "com.novabank.upi.NpciClient"),
								new Msg("UPI PIN retry limit nearing for {vpa}", "com.novabank.upi.PinService")),
						List.of(new Msg("UPI transaction {upitxn} failed: U30 debit failed at remitter bank", "com.novabank.upi.PayHandler"),
								new Msg("Kafka publish failed for topic upi.txn.events: NotLeaderForPartitionException", "com.novabank.upi.EventPublisher")),
						List.of(),
						List.of(new Msg("NPCI switch timeout for {upitxn} after 30000ms (RespCode BT)", "com.novabank.upi.NpciClient"),
								new Msg("UPI transaction {upitxn} deemed failed: no response from NPCI", "com.novabank.upi.NpciClient"))),

				new Profile("card-auth", LogFormat.LOGFMT, 10, "card-auth",
						List.of(new Msg("Card authorisation approved card={card} mcc=5411 amount={amt} auth_code={code}", ""),
								new Msg("Contactless tap approved card={card} amount={amt}", ""),
								new Msg("E-commerce 3DS challenge passed card={card}", "")),
						List.of(new Msg("Card declined insufficient funds card={card} amount={amt}", ""),
								new Msg("CVV mismatch card={card}, attempt 2/3", "")),
						List.of(new Msg("ISO8583 message parse error from Visa network: field 55 malformed", ""),
								new Msg("Tokenisation service unavailable: connection refused", "")),
						List.of(),
						List.of(new Msg("HSM connection refused while verifying PIN block for card={card}", ""),
								new Msg("Card network authorisation timeout for card={card} after 10000ms", ""))),

				new Profile("netbanking-portal", LogFormat.ACCESS_LOG, 14, "nb-web", List.of(new Msg("", "")),
						List.of(new Msg("", "")), List.of(new Msg("", "")), List.of(), List.of(new Msg("", ""))),

				new Profile("atm-switch", LogFormat.SYSLOG_RFC3164, 7, "atm-sw",
						List.of(new Msg("{atm} cash withdrawal approved card {card} amount INR {amt}", ""),
								new Msg("{atm} heartbeat OK, cash level {pct}%", ""),
								new Msg("{atm} balance enquiry served for card {card}", "")),
						List.of(new Msg("{atm} cash low: {lowpct}% remaining", ""),
								new Msg("{atm} card retained after 3 wrong PIN attempts", "")),
						List.of(new Msg("{atm} dispense failure: cassette 2 jammed, reversal initiated for {txn}", ""),
								new Msg("ISO8583 0210 response timeout from switch for {atm}", "")),
						List.of(),
						List.of(new Msg("Link to card switch DOWN for {atm}: connection reset by peer", ""))),

				new Profile("loan-origination", LogFormat.LOG4J, 6, "los-app",
						List.of(new Msg("Loan application LA{num} submitted for {loantype}, amount INR {amt}", loan + "ApplicationService"),
								new Msg("Credit score fetched for applicant APP{num}: {score}", loan + "bureau.CreditBureauClient"),
								new Msg("Loan LA{num} sanctioned, disbursal scheduled", loan + "SanctionService")),
						List.of(new Msg("Applicant APP{num} debt-to-income ratio {pct}% exceeds policy threshold", loan + "PolicyEngine")),
						List.of(new Msg("CIBIL credit bureau API returned HTTP 500 for APP{num}", loan + "bureau.CreditBureauClient"),
								new Msg("IllegalStateException: Loan LA{num} in SANCTIONED state cannot be re-underwritten", loan + "UnderwritingService", true)),
						List.of(),
						List.of(new Msg("Credit bureau request timed out after 15000ms for APP{num}", loan + "bureau.CreditBureauClient"))),

				new Profile("kyc-service", LogFormat.PYTHON, 5, "kyc",
						List.of(new Msg("Video KYC session VK{num} completed for CIF{cif}", ""),
								new Msg("PAN verification successful for CIF{cif}", ""),
								new Msg("Re-KYC reminder sent to CIF{cif}", "")),
						List.of(new Msg("Aadhaar OTP delivery delayed for CIF{cif}", "")),
						List.of(new Msg("CKYC registry upload failed for CIF{cif}: schema validation error", ""),
								new Msg("Traceback: KeyError 'dob' while parsing PAN response for CIF{cif}", "")),
						List.of(),
						List.of(new Msg("Aadhaar eKYC API timeout for CIF{cif} after 20000ms", ""))),

				new Profile("fraud-engine", LogFormat.SYSLOG_RFC5424, 8, "fraud-node",
						List.of(new Msg("Transaction {txn} scored {fraudscore}/1000 - ALLOW", ""),
								new Msg("Rules engine reloaded: 412 active rules", "")),
						List.of(new Msg("Velocity rule V12 triggered for acct {acct}: 8 txns in 5 min", ""),
								new Msg("Device fingerprint mismatch for CIF{cif}, step-up auth requested", "")),
						List.of(new Msg("Suspicious transaction {txn} blocked: mule account pattern detected", ""),
								new Msg("Fraud model inference failed: model server unreachable", "")),
						List.of(),
						List.of(new Msg("Fraud scoring timeout for {txn}, failing open", ""))),

				new Profile("treasury-fx", LogFormat.PIPE_DELIMITED, 3, "fx",
						List.of(new Msg("FX deal FX{num} booked {pair} @ {rate}, notional USD {big}", ""),
								new Msg("Market data tick received for {pair}: {rate}", "")),
						List.of(new Msg("Rate feed stale for {pair}: last tick {n}s ago", "")),
						List.of(new Msg("SWIFT MT300 confirmation failed for deal FX{num}: NAK received", ""),
								new Msg("Settlement instruction mismatch for deal FX{num}", "")),
						List.of(), List.of(new Msg("SWIFT gateway connection lost, {n} confirmations queued", ""))),

				new Profile("insurance-claims", LogFormat.GENERIC, 3, "claims",
						List.of(new Msg("Claim CLM{num} registered for policy POL{num}, amount INR {amt}", ""),
								new Msg("Claim CLM{num} approved by surveyor", "")),
						List.of(new Msg("Claim CLM{num} pending documents for 7 days", "")),
						List.of(new Msg("Insurer API returned 500 while syncing claim CLM{num}", ""),
								new Msg("PDF generation failed for claim CLM{num}: OutOfMemoryError", "")),
						List.of(), List.of(new Msg("Insurer API unreachable, claim sync halted", ""))),

				new Profile("mobile-banking-api", LogFormat.JSON, 12, "mb-api",
						List.of(new Msg("Login successful for CIF{cif} via biometric", "com.novabank.mb.AuthController"),
								new Msg("Mini statement served for acct {acct}", "com.novabank.mb.AccountController"),
								new Msg("Beneficiary added for CIF{cif} after cooling period", "com.novabank.mb.PayeeController")),
						List.of(new Msg("OTP verification failed for CIF{cif}, attempt 2/3", "com.novabank.mb.AuthController"),
								new Msg("App version 4.1.0 deprecated, prompting upgrade", "com.novabank.mb.VersionFilter")),
						List.of(new Msg("JWT validation failed: token expired for session {sess}", "com.novabank.mb.security.JwtFilter"),
								new Msg("Push notification vendor returned HTTP 503", "com.novabank.mb.notify.PushClient")),
						List.of(),
						List.of(new Msg("Authentication service unavailable: login failed for CIF{cif}", "com.novabank.mb.AuthController"))));
	}
}
