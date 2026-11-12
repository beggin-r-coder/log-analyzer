package com.anishka.log_analyzer.incident;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.anishka.log_analyzer.entity.Incident;
import com.anishka.log_analyzer.entity.LogRecord;
import com.anishka.log_analyzer.repository.IncidentRepository;
import com.anishka.log_analyzer.repository.LogRecordRepository;
import com.anishka.log_analyzer.service.TimeRange;

/**
 * Turns log volumes into numbers a manager cares about: money at risk, failed
 * customer transactions, customers affected, SLA/error-budget per service and
 * how fast incidents are acknowledged and fixed (MTTA / MTTR).
 *
 * <p>All money figures are estimates: amounts are read from the failed events
 * where present, otherwise from the median ticket size of successful events of
 * the same service.
 */
@Service
@Transactional(readOnly = true)
public class ImpactService {

	/** Availability is measured in slots of this many minutes. */
	public static final int SLOT_MINUTES = 5;

	private static final Pattern AMOUNT = Pattern.compile("(?:INR |amount=)([\\d,]+(?:\\.\\d{1,2})?)");
	private static final Pattern TXN_ID = Pattern.compile("\\b(TXN\\d{8,}|NOVA\\d{10,}|ORD\\d{6,}|SI\\d{4,})\\b");
	private static final Pattern CUSTOMER_ID = Pattern
			.compile("(CIF\\d{4,}|acct [X\\d]{6,}|card[= ][\\dX]{12,19}|\\b[a-z]\\*{4}\\d{2}@[a-z]+)");
	/** Customers retry a failed payment; failures without a customer id are assumed to be this many attempts each. */
	static final double ATTEMPTS_PER_CUSTOMER = 1.3;
	private static final Pattern NB_MONEY_PATH = Pattern.compile("fund-transfer|upi|bill-pay|payment");

	private final LogRecordRepository logs;
	private final IncidentRepository incidents;
	private final ServiceCatalog catalog;

	public ImpactService(LogRecordRepository logs, IncidentRepository incidents,
			@Value("${app.impact.sla-targets:upi-switch:99.5,core-banking:99.9,card-auth:99.5,payment-gateway:99.5,netbanking-portal:99.5,mobile-banking-api:99.5,atm-switch:99.5}") String slaTargets,
			@Value("${app.impact.default-sla:99.0}") double defaultSla) {
		this.logs = logs;
		this.incidents = incidents;
		this.catalog = new ServiceCatalog(slaTargets, defaultSla);
	}

	public ServiceCatalog catalog() {
		return catalog;
	}

	public Map<String, Object> summary(String rangeCode) {
		TimeRange range = TimeRange.of(rangeCode, TimeRange.H24);
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime from = now.minus(range.duration());
		LocalDateTime earliest = logs.findTopByOrderByTimestampAsc().map(LogRecord::getTimestamp).orElse(now);
		if (earliest.isAfter(from)) {
			from = earliest; // never count time before we had data as "available"
		}
		return compute(range.code(), from, now);
	}

	/** Impact of a single time window (used for an incident's own window). */
	public Map<String, Object> window(LocalDateTime from, LocalDateTime to, String onlyService) {
		Money m = money(from, to, onlyService == null ? ServiceCatalog.PAYMENT_SERVICES : Set.of(onlyService));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("valueAtRisk", round(m.value));
		out.put("failedTransactions", m.failedTxns);
		out.put("customersAffected", m.customers);
		return out;
	}

	Map<String, Object> compute(String rangeCode, LocalDateTime from, LocalDateTime to) {
		LocalDateTime toEx = to.plusMinutes(1);
		Money money = money(from, toEx, ServiceCatalog.PAYMENT_SERVICES);
		List<Map<String, Object>> sla = sla(from, to);
		Map<String, Object> response = responseTimes(from);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("range", rangeCode);
		out.put("from", epoch(from));
		out.put("to", epoch(to));
		out.put("currency", "INR");
		out.put("valueAtRisk", round(money.value));
		out.put("failedTransactions", money.failedTxns);
		out.put("customersAffected", money.customers);
		out.put("byService", money.byService);
		out.put("sla", sla);
		out.putAll(response);
		long breached = sla.stream().filter(s -> "BREACHED".equals(s.get("status"))).count();
		long atRisk = sla.stream().filter(s -> "AT_RISK".equals(s.get("status"))).count();
		out.put("slaBreached", breached);
		out.put("slaAtRisk", atRisk);
		out.put("slaMet", sla.size() - breached - atRisk);
		out.put("headline", headline(money, sla));
		out.put("method", "Estimated from ERROR/FATAL events of payment services. Amounts come from the failed event "
				+ "when logged, otherwise the median successful ticket size of that service. Customers are counted once per "
				+ "account, card, VPA or CIF; failures without one assume " + ATTEMPTS_PER_CUSTOMER + " attempts per customer. Availability is measured in "
				+ SLOT_MINUTES + "-minute slots; a slot is unavailable when it has ≥3 errors and an error rate ≥5%.");
		return out;
	}

	// ---------------------------------------------------------------- money

	private record Money(double value, long failedTxns, long customers, List<Map<String, Object>> byService) {
	}

	private Money money(LocalDateTime from, LocalDateTime to, Set<String> services) {
		Map<String, Double> tickets = typicalTickets(to);
		Map<String, double[]> agg = new HashMap<>(); // service -> [value, txns, known customers, anonymous failures]
		Set<String> txnIds = new HashSet<>();
		Set<String> customerIds = new HashSet<>();
		long anonCustomers = 0;
		for (LogRecord r : logs.failedEvents(from, to, services)) {
			String msg = r.getMessage() == null ? "" : r.getMessage();
			if ("netbanking-portal".equals(r.getService()) && !NB_MONEY_PATH.matcher(msg).find()) {
				continue; // a failed page view is not a failed payment
			}
			double[] a = agg.computeIfAbsent(r.getService(), k -> new double[4]);
			Matcher tm = TXN_ID.matcher(msg);
			if (tm.find() && !txnIds.add(tm.group(1))) {
				continue; // a retry of a transaction we already counted
			}
			Matcher am = AMOUNT.matcher(msg);
			double amount = am.find() ? parse(am.group(1)) : tickets.getOrDefault(r.getService(), ServiceCatalog.defaultTicket(r.getService()));
			a[0] += amount;
			a[1]++;
			Matcher cm = CUSTOMER_ID.matcher(msg);
			if (cm.find()) {
				if (customerIds.add(cm.group(1))) {
					a[2]++;
				}
			} else {
				anonCustomers++;
				a[3]++;
			}
		}
		List<Map<String, Object>> byService = new ArrayList<>();
		double total = 0;
		long txns = 0;
		for (Map.Entry<String, double[]> e : agg.entrySet()) {
			double[] a = e.getValue();
			total += a[0];
			txns += (long) a[1];
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("service", e.getKey());
			m.put("valueAtRisk", round(a[0]));
			m.put("failedTransactions", (long) a[1]);
			m.put("customersAffected", (long) a[2] + Math.round(a[3] / ATTEMPTS_PER_CUSTOMER));
			m.put("owner", ServiceCatalog.owner(e.getKey()));
			byService.add(m);
		}
		byService.sort(Comparator.comparingDouble((Map<String, Object> m) -> ((Number) m.get("valueAtRisk")).doubleValue()).reversed());
		return new Money(total, txns, customerIds.size() + Math.round(anonCustomers / ATTEMPTS_PER_CUSTOMER), byService);
	}

	/** Median amount of successful events per service, from the last day of INFO logs. */
	private Map<String, Double> typicalTickets(LocalDateTime to) {
		Map<String, List<Double>> samples = new HashMap<>();
		for (Object[] row : logs.amountSamples(to.minusDays(1), PageRequest.of(0, 4000))) {
			Matcher m = AMOUNT.matcher(String.valueOf(row[1]));
			if (m.find()) {
				samples.computeIfAbsent((String) row[0], k -> new ArrayList<>()).add(parse(m.group(1)));
			}
		}
		Map<String, Double> out = new HashMap<>();
		samples.forEach((svc, list) -> {
			if (list.size() >= 5) {
				Collections.sort(list);
				out.put(svc, list.get(list.size() / 2));
			}
		});
		return out;
	}

	// ---------------------------------------------------------------- SLA

	private List<Map<String, Object>> sla(LocalDateTime from, LocalDateTime to) {
		long slots = Math.max(1, Duration.between(from, to).toMinutes() / SLOT_MINUTES);
		Map<String, long[]> perService = new HashMap<>(); // [bad slots, events, errors]
		for (Object[] row : logs.serviceBucketCounts(from, to.plusMinutes(1), SLOT_MINUTES)) {
			String svc = (String) row[0];
			long events = ((Number) row[2]).longValue();
			long errors = ((Number) row[3]).longValue();
			long[] a = perService.computeIfAbsent(svc, k -> new long[3]);
			a[1] += events;
			a[2] += errors;
			if (isBadSlot(events, errors)) {
				a[0]++;
			}
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map.Entry<String, long[]> e : perService.entrySet()) {
			String svc = e.getKey();
			long bad = e.getValue()[0];
			double target = catalog.slaTarget(svc);
			double availability = 100.0 * (slots - Math.min(bad, slots)) / slots;
			double budgetMinutes = (100 - target) / 100.0 * slots * SLOT_MINUTES;
			double downMinutes = bad * SLOT_MINUTES;
			double used = budgetMinutes <= 0 ? (downMinutes > 0 ? 100 : 0) : downMinutes * 100 / budgetMinutes;
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("service", svc);
			m.put("owner", ServiceCatalog.owner(svc));
			m.put("target", target);
			m.put("availability", Math.round(availability * 1000) / 1000.0);
			m.put("downMinutes", downMinutes);
			m.put("budgetMinutes", Math.round(budgetMinutes * 10) / 10.0);
			m.put("budgetUsed", Math.round(used * 10) / 10.0);
			m.put("status", used >= 100 ? "BREACHED" : used >= 75 ? "AT_RISK" : "MET");
			m.put("events", e.getValue()[1]);
			m.put("errors", e.getValue()[2]);
			out.add(m);
		}
		out.sort(Comparator.comparingDouble((Map<String, Object> m) -> ((Number) m.get("budgetUsed")).doubleValue()).reversed()
				.thenComparing(m -> (String) m.get("service")));
		return out;
	}

	static boolean isBadSlot(long events, long errors) {
		return errors >= 3 && errors * 100.0 / Math.max(1, events) >= 5;
	}

	// ---------------------------------------------------------------- MTTA / MTTR

	private Map<String, Object> responseTimes(LocalDateTime from) {
		List<Incident> list = incidents.findByOpenedAtGreaterThanEqualOrderByOpenedAtDesc(from);
		double ackSum = 0, fixSum = 0;
		int ackN = 0, fixN = 0;
		for (Incident i : list) {
			if (i.getAcknowledgedAt() != null) {
				ackSum += Duration.between(i.getOpenedAt(), i.getAcknowledgedAt()).toSeconds();
				ackN++;
			}
			if (i.getResolvedAt() != null) {
				fixSum += Duration.between(i.getOpenedAt(), i.getResolvedAt()).toSeconds();
				fixN++;
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("incidents", list.size());
		out.put("openIncidents", list.stream().filter(Incident::isActive).count());
		out.put("mttaMinutes", ackN == 0 ? null : Math.round(ackSum / ackN / 6.0) / 10.0);
		out.put("mttrMinutes", fixN == 0 ? null : Math.round(fixSum / fixN / 6.0) / 10.0);
		return out;
	}

	// ---------------------------------------------------------------- helpers

	private String headline(Money money, List<Map<String, Object>> sla) {
		StringBuilder sb = new StringBuilder();
		if (money.failedTxns == 0) {
			sb.append("No failed customer transactions in this period.");
		} else {
			sb.append(inr(money.value)).append(" in ").append(String.format("%,d", money.failedTxns))
					.append(" failed customer transactions");
			if (!money.byService.isEmpty()) {
				Map<String, Object> top = money.byService.get(0);
				sb.append(", mostly on ").append(top.get("service"));
			}
			sb.append('.');
		}
		List<String> breached = sla.stream().filter(s -> "BREACHED".equals(s.get("status")))
				.map(s -> (String) s.get("service")).toList();
		if (!breached.isEmpty()) {
			sb.append(' ').append(String.join(", ", breached)).append(breached.size() == 1 ? " is" : " are")
					.append(" out of SLA.");
		} else {
			sb.append(" All services within SLA.");
		}
		return sb.toString();
	}

	/** Indian-style money: ₹4.2 L, ₹1.3 Cr. */
	public static String inr(double v) {
		if (v >= 1e7) return String.format("₹%.2f Cr", v / 1e7);
		if (v >= 1e5) return String.format("₹%.2f L", v / 1e5);
		return String.format("₹%,.0f", v);
	}

	private static double parse(String s) {
		try {
			return Double.parseDouble(s.replace(",", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static double round(double v) {
		return Math.round(v * 100) / 100.0;
	}

	static long epoch(LocalDateTime t) {
		return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}
}
