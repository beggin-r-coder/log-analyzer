package com.anishka.log_analyzer.incident;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.anishka.log_analyzer.ai.RecoveryPlaybook;
import com.anishka.log_analyzer.entity.AlertRule;
import com.anishka.log_analyzer.entity.Incident;
import com.anishka.log_analyzer.entity.IncidentEvent;
import com.anishka.log_analyzer.entity.LogRecord;
import com.anishka.log_analyzer.repository.AlertRuleRepository;
import com.anishka.log_analyzer.repository.IncidentEventRepository;
import com.anishka.log_analyzer.repository.IncidentRepository;
import com.anishka.log_analyzer.repository.LogRecordRepository;
import com.anishka.log_analyzer.service.TimeRange;
import com.anishka.log_analyzer.simulator.LiveLogSimulator;
import com.anishka.log_analyzer.stream.LogStreamBroadcaster;

/**
 * The incident workflow: alert rules are evaluated against the log store every
 * few seconds; a breach opens an incident owned by the service's on-call team,
 * which people then acknowledge, annotate and resolve. Incidents also close by
 * themselves once a service has been healthy for {@link #RECOVERY_MINUTES}.
 */
@Service
public class IncidentService {

	private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

	static final int RECOVERY_MINUTES = 10;
	/** After a person resolves an incident, the same service is not re-alerted for this long. */
	static final int SNOOZE_AFTER_MANUAL_RESOLVE_MINUTES = 5;
	private static final Set<String> ACTIVE = Set.of(Incident.OPEN, Incident.ACKNOWLEDGED);
	private static final LocalDateTime BUCKET_ORIGIN = LocalDateTime.of(2000, 1, 1, 0, 0);
	private static final List<String> SEVERITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

	private final IncidentRepository incidents;
	private final IncidentEventRepository events;
	private final AlertRuleRepository rules;
	private final LogRecordRepository logs;
	private final RecoveryPlaybook playbook;
	private final IncidentNotifier notifier;
	private final LogStreamBroadcaster broadcaster;
	private final ObjectProvider<LiveLogSimulator> simulator;

	private volatile boolean historyChecked;

	public IncidentService(IncidentRepository incidents, IncidentEventRepository events, AlertRuleRepository rules,
			LogRecordRepository logs, RecoveryPlaybook playbook, IncidentNotifier notifier,
			LogStreamBroadcaster broadcaster, ObjectProvider<LiveLogSimulator> simulator) {
		this.incidents = incidents;
		this.events = events;
		this.rules = rules;
		this.logs = logs;
		this.playbook = playbook;
		this.notifier = notifier;
		this.broadcaster = broadcaster;
		this.simulator = simulator;
	}

	// =====================================================================
	// RULES
	// =====================================================================

	@Transactional
	public List<AlertRule> rules() {
		if (rules.count() == 0) {
			rules.saveAll(List.of(
					new AlertRule("High error rate", "*", AlertRule.ERROR_RATE, 15, 5, 5, "HIGH"),
					new AlertRule("UPI payment failures", "upi-switch", AlertRule.ERROR_COUNT, 8, 5, 0, "CRITICAL"),
					new AlertRule("Core banking posting errors", "core-banking", AlertRule.ERROR_COUNT, 6, 5, 0, "CRITICAL"),
					new AlertRule("Card authorisation failures", "card-auth", AlertRule.ERROR_COUNT, 8, 5, 0, "HIGH"),
					new AlertRule("Sustained errors (any service)", "*", AlertRule.ERROR_COUNT, 25, 15, 0, "MEDIUM")));
		}
		return rules.findAll(org.springframework.data.domain.Sort.by("id"));
	}

	@Transactional
	public AlertRule saveRule(Long id, AlertRule in) {
		AlertRule r = id == null ? new AlertRule() : rules.findById(id).orElseThrow(() -> new NotFound("rule " + id));
		r.setName(blankTo(in.getName(), "Untitled rule"));
		r.setService(blankTo(in.getService(), "*"));
		r.setMetric(AlertRule.ERROR_COUNT.equals(in.getMetric()) ? AlertRule.ERROR_COUNT : AlertRule.ERROR_RATE);
		r.setThreshold(Math.max(0.1, in.getThreshold()));
		r.setWindowMinutes(Math.max(1, Math.min(in.getWindowMinutes(), 120)));
		r.setMinErrors(Math.max(0, in.getMinErrors()));
		r.setSeverity(SEVERITIES.contains(in.getSeverity()) ? in.getSeverity() : "HIGH");
		r.setEnabled(in.isEnabled());
		return rules.save(r);
	}

	@Transactional
	public void deleteRule(Long id) {
		rules.deleteById(id);
	}

	static boolean breaches(AlertRule r, long events, long errors) {
		if (AlertRule.ERROR_COUNT.equals(r.getMetric())) {
			return errors >= r.getThreshold();
		}
		return events > 0 && errors >= Math.max(1, r.getMinErrors()) && errors * 100.0 / events >= r.getThreshold();
	}

	// =====================================================================
	// LIVE EVALUATION
	// =====================================================================

	@Scheduled(fixedDelayString = "${app.incidents.evaluate-ms:20000}", initialDelayString = "${app.incidents.initial-delay-ms:10000}")
	public void scheduledEvaluate() {
		LiveLogSimulator sim = simulator.getIfAvailable();
		if (sim != null && Boolean.TRUE.equals(sim.status().get("seeding"))) {
			return;
		}
		try {
			if (!historyChecked) {
				historyChecked = true;
				backfillIfEmpty();
			}
			evaluate(LocalDateTime.now());
		} catch (RuntimeException e) {
			log.warn("Incident evaluation failed: {}", e.toString());
		}
	}

	@Transactional
	public void evaluate(LocalDateTime now) {
		List<AlertRule> enabled = rules().stream().filter(AlertRule::isEnabled).toList();
		// service -> strongest breach
		Map<String, Breach> breaches = new HashMap<>();
		Map<Integer, List<AlertRule>> byWindow = enabled.stream().collect(Collectors.groupingBy(AlertRule::getWindowMinutes));
		for (Map.Entry<Integer, List<AlertRule>> w : byWindow.entrySet()) {
			for (Object[] row : logs.serviceStats(now.minusMinutes(w.getKey()), now.plusSeconds(1))) {
				String svc = (String) row[0];
				long total = ((Number) row[1]).longValue();
				long errors = ((Number) row[3]).longValue();
				for (AlertRule r : w.getValue()) {
					if (r.appliesTo(svc) && breaches(r, total, errors)) {
						Breach b = new Breach(r, total, errors, w.getKey());
						breaches.merge(svc, b, (x, y) -> rank(y.rule.getSeverity()) > rank(x.rule.getSeverity()) ? y : x);
					}
				}
			}
		}
		for (Map.Entry<String, Breach> e : breaches.entrySet()) {
			onBreach(e.getKey(), e.getValue(), now);
		}
		for (Incident i : incidents.findByStatusInOrderByOpenedAtDesc(ACTIVE)) {
			if (!breaches.containsKey(i.getService()) && i.getLastBreachAt() != null
					&& i.getLastBreachAt().isBefore(now.minusMinutes(RECOVERY_MINUTES))) {
				i.setStatus(Incident.RESOLVED);
				i.setResolvedAt(now);
				i.setResolvedBy("auto-recovery");
				incidents.save(i);
				addEvent(i, now, "RECOVERED", "system", "Healthy for " + RECOVERY_MINUTES
						+ " minutes — auto-resolved. Errors last breached the rule at " + hhmm(i.getLastBreachAt()) + ".");
				changed(i, "resolved");
			}
		}
	}

	private record Breach(AlertRule rule, long events, long errors, int window) {
		double rate() {
			return events == 0 ? 0 : errors * 100.0 / events;
		}
	}

	private void onBreach(String svc, Breach b, LocalDateTime now) {
		Incident active = incidents.findFirstByServiceAndStatusIn(svc, ACTIVE).orElse(null);
		if (active == null) {
			boolean snoozed = incidents.findByOpenedAtGreaterThanEqualOrderByOpenedAtDesc(now.minusHours(2)).stream()
					.anyMatch(i -> i.getService().equals(svc) && i.getResolvedAt() != null
							&& !"auto-recovery".equals(i.getResolvedBy())
							&& i.getResolvedAt().isAfter(now.minusMinutes(SNOOZE_AFTER_MANUAL_RESOLVE_MINUTES)));
			if (snoozed) {
				return;
			}
			Incident i = open(svc, b, now, now.minusMinutes(b.window));
			changed(i, "opened");
			notifier.opened(i);
			return;
		}
		active.setLastBreachAt(now);
		active.setPeakErrorRate(Math.max(active.getPeakErrorRate(), round1(b.rate())));
		if (rank(b.rule.getSeverity()) > rank(active.getSeverity())) {
			addEvent(active, now, "UPDATED", "system", "Escalated " + active.getSeverity() + " → "
					+ b.rule.getSeverity() + " by rule \"" + b.rule.getName() + "\" (" + describe(b) + ").");
			active.setSeverity(b.rule.getSeverity());
			changed(active, "escalated");
		}
		incidents.save(active);
	}

	private Incident open(String svc, Breach b, LocalDateTime at, LocalDateTime windowStart) {
		List<LogRecord> errors = logs.findTop300ByServiceAndLevelRankGreaterThanEqualAndTimestampBetweenOrderByTimestampAsc(
				svc, 4, windowStart, at.plusSeconds(1));
		String category = errors.stream().collect(Collectors.groupingBy(r -> String.valueOf(r.getErrorCategory()),
				Collectors.counting())).entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
				.orElse("Errors");
		LogRecord sample = errors.isEmpty() ? null : errors.get(errors.size() - 1);
		RecoveryPlaybook.Play play = sample == null ? null : playbook.match(sample.getMessage());

		Incident i = new Incident();
		i.setService(svc);
		i.setTitle(truncate((play != null && !"generic".equals(play.id()) ? play.title() : category) + " on " + svc, 200));
		i.setSeverity(b.rule.getSeverity());
		i.setStatus(Incident.OPEN);
		i.setRuleId(b.rule.getId());
		i.setRuleName(b.rule.getName());
		i.setOwner(ServiceCatalog.owner(svc));
		i.setOpenedAt(at);
		i.setLastBreachAt(at);
		i.setPeakErrorRate(round1(b.rate()));
		incidents.save(i);
		StringBuilder text = new StringBuilder("Rule \"").append(b.rule.getName()).append("\" fired: ").append(describe(b))
				.append(". Paged ").append(i.getOwner()).append(" on-call.");
		if (sample != null) {
			text.append("\nLatest error: ").append(truncate(sample.getMessage(), 300));
		}
		if (play != null && !play.recovery().isEmpty()) {
			text.append("\nSuggested first step: ").append(play.recovery().get(0));
		}
		addEvent(i, at, "OPENED", "system", text.toString());
		return i;
	}

	private static String describe(Breach b) {
		return String.format("%d errors / %d events (%.1f%%) in %d min", b.errors, b.events, b.rate(), b.window);
	}

	// =====================================================================
	// HISTORY
	// =====================================================================

	@Transactional
	public void backfillIfEmpty() {
		if (incidents.count() > 0 || logs.count() == 0) {
			return;
		}
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime from = logs.findTopByOrderByTimestampAsc().map(LogRecord::getTimestamp).orElse(now);
		int n = backfill(from, now);
		log.info("Reconstructed {} historic incidents from stored logs", n);
	}

	/**
	 * Replays the 5-minute alert rules over stored history and records the incidents
	 * they would have raised, with plausible on-call acknowledgement and fix times.
	 * Incidents still ongoing at {@code to} are left to live evaluation.
	 */
	@Transactional
	public int backfill(LocalDateTime from, LocalDateTime to) {
		int slot = ImpactService.SLOT_MINUTES;
		List<AlertRule> slotRules = rules().stream().filter(r -> r.isEnabled() && r.getWindowMinutes() <= slot).toList();
		if (slotRules.isEmpty()) {
			return 0;
		}
		Map<String, TreeMap<Long, long[]>> series = new HashMap<>();
		for (Object[] row : logs.serviceBucketCounts(from, to, slot)) {
			series.computeIfAbsent((String) row[0], k -> new TreeMap<>()).put(((Number) row[1]).longValue(),
					new long[] { ((Number) row[2]).longValue(), ((Number) row[3]).longValue() });
		}
		int created = 0;
		for (Map.Entry<String, TreeMap<Long, long[]>> s : series.entrySet()) {
			String svc = s.getKey();
			Long openBucket = null, lastBad = null;
			Breach worst = null;
			for (Map.Entry<Long, long[]> e : s.getValue().entrySet()) {
				long b = e.getKey();
				if (openBucket != null && b > lastBad + RECOVERY_MINUTES / slot) {
					created += recordHistoric(svc, worst, openBucket, lastBad, to);
					openBucket = null;
				}
				Breach hit = null;
				for (AlertRule r : slotRules) {
					if (r.appliesTo(svc) && breaches(r, e.getValue()[0], e.getValue()[1])
							&& (hit == null || rank(r.getSeverity()) > rank(hit.rule.getSeverity()))) {
						hit = new Breach(r, e.getValue()[0], e.getValue()[1], slot);
					}
				}
				if (hit != null) {
					if (openBucket == null) {
						openBucket = b;
						worst = hit;
					} else if (rank(hit.rule.getSeverity()) > rank(worst.rule.getSeverity()) || hit.rate() > worst.rate()) {
						worst = hit;
					}
					lastBad = b;
				}
			}
			if (openBucket != null && bucketStart(lastBad + RECOVERY_MINUTES / slot + 1).isBefore(to)) {
				created += recordHistoric(svc, worst, openBucket, lastBad, to);
			}
		}
		return created;
	}

	private int recordHistoric(String svc, Breach worst, long openBucket, long lastBad, LocalDateTime to) {
		int slot = ImpactService.SLOT_MINUTES;
		LocalDateTime opened = bucketStart(openBucket).plusMinutes(slot);
		LocalDateTime lastBreach = bucketStart(lastBad).plusMinutes(slot);
		Random r = new Random(opened.hashCode() ^ svc.hashCode());
		LocalDateTime acked = opened.plusSeconds(60 + r.nextInt(420));
		LocalDateTime resolved = lastBreach.plusSeconds(120 + r.nextInt(600));
		if (!acked.isBefore(resolved)) {
			acked = opened.plusSeconds(Duration.between(opened, resolved).toSeconds() / 3);
		}
		if (!resolved.isBefore(to)) {
			return 0;
		}
		Incident i = open(svc, worst, opened, bucketStart(openBucket));
		String team = i.getOwner() + " on-call";
		i.setLastBreachAt(lastBreach);
		i.setAcknowledgedAt(acked);
		i.setAcknowledgedBy(team);
		i.setResolvedAt(resolved);
		i.setResolvedBy(team);
		i.setStatus(Incident.RESOLVED);
		incidents.save(i);
		addEvent(i, acked, "ACKNOWLEDGED", team, "Acknowledged, investigating.");
		RecoveryPlaybook.Play play = playbook.match(i.getTitle());
		String fix = play != null && play.recovery().size() > 1 ? play.recovery().get(r.nextInt(play.recovery().size()))
				: "Mitigated; error rate back to baseline.";
		addEvent(i, resolved, "RESOLVED", team, "Resolved. " + fix);
		return 1;
	}

	private static LocalDateTime bucketStart(long bucket) {
		return BUCKET_ORIGIN.plusMinutes(bucket * ImpactService.SLOT_MINUTES);
	}

	// =====================================================================
	// PEOPLE ACTIONS
	// =====================================================================

	@Transactional
	public Incident acknowledge(long id, String user) {
		Incident i = get(id);
		if (!Incident.OPEN.equals(i.getStatus())) {
			return i;
		}
		LocalDateTime now = LocalDateTime.now();
		i.setStatus(Incident.ACKNOWLEDGED);
		i.setAcknowledgedAt(now);
		i.setAcknowledgedBy(user);
		incidents.save(i);
		addEvent(i, now, "ACKNOWLEDGED", user, "Acknowledged, investigating.");
		changed(i, "acknowledged");
		return i;
	}

	@Transactional
	public Incident resolve(long id, String user, String note) {
		Incident i = get(id);
		if (!i.isActive()) {
			return i;
		}
		LocalDateTime now = LocalDateTime.now();
		if (i.getAcknowledgedAt() == null) {
			i.setAcknowledgedAt(now);
			i.setAcknowledgedBy(user);
		}
		i.setStatus(Incident.RESOLVED);
		i.setResolvedAt(now);
		i.setResolvedBy(user);
		incidents.save(i);
		addEvent(i, now, "RESOLVED", user, note == null || note.isBlank() ? "Resolved." : "Resolved. " + note.trim());
		changed(i, "resolved");
		notifier.resolved(i);
		return i;
	}

	@Transactional
	public IncidentEvent note(long id, String user, String text) {
		Incident i = get(id);
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("Note is empty");
		}
		IncidentEvent e = addEvent(i, LocalDateTime.now(), "NOTE", user, truncate(text.trim(), 2000));
		changed(i, "note");
		return e;
	}

	// =====================================================================
	// QUERIES
	// =====================================================================

	@Transactional(readOnly = true)
	public Map<String, Object> list(String status, String rangeCode) {
		TimeRange range = TimeRange.of(rangeCode, TimeRange.D7);
		LocalDateTime from = LocalDateTime.now().minus(range.duration());
		List<Incident> inRange = incidents.findByOpenedAtGreaterThanEqualOrderByOpenedAtDesc(from);
		// active incidents are always listed, even if they opened before the range
		Map<Long, Incident> all = new LinkedHashMap<>();
		incidents.findByStatusInOrderByOpenedAtDesc(ACTIVE).forEach(i -> all.put(i.getId(), i));
		inRange.forEach(i -> all.putIfAbsent(i.getId(), i));
		List<Incident> sorted = new ArrayList<>(all.values());
		sorted.sort(Comparator.comparing((Incident i) -> !i.isActive()).thenComparing(Incident::getOpenedAt, Comparator.reverseOrder()));

		List<Map<String, Object>> items = sorted.stream()
				.filter(i -> status == null || status.isBlank() || "all".equalsIgnoreCase(status)
						|| ("active".equalsIgnoreCase(status) ? i.isActive() : i.getStatus().equalsIgnoreCase(status)))
				.map(IncidentService::dto).toList();

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("range", range.code());
		out.put("open", all.values().stream().filter(i -> Incident.OPEN.equals(i.getStatus())).count());
		out.put("acknowledged", all.values().stream().filter(i -> Incident.ACKNOWLEDGED.equals(i.getStatus())).count());
		out.put("resolved", inRange.stream().filter(i -> !i.isActive()).count());
		out.put("total", inRange.size());
		out.put("items", items);
		return out;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> detail(long id, ImpactService impact) {
		Incident i = get(id);
		Map<String, Object> out = dto(i);
		out.put("timeline", events.findByIncidentIdOrderByAtAscIdAsc(id).stream().map(e -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", e.getId());
			m.put("at", ImpactService.epoch(e.getAt()));
			m.put("type", e.getType());
			m.put("actor", e.getActor());
			m.put("text", e.getText());
			return m;
		}).toList());
		LocalDateTime start = i.getOpenedAt().minusMinutes(ImpactService.SLOT_MINUTES);
		LocalDateTime end = i.getResolvedAt() != null ? i.getResolvedAt() : LocalDateTime.now();
		out.put("impact", ServiceCatalog.PAYMENT_SERVICES.contains(i.getService()) ? impact.window(start, end, i.getService()) : null);
		out.put("windowFrom", ImpactService.epoch(start));
		out.put("windowTo", ImpactService.epoch(end));
		return out;
	}

	@Transactional(readOnly = true)
	public long activeCount() {
		return incidents.findByStatusInOrderByOpenedAtDesc(ACTIVE).size();
	}

	static Map<String, Object> dto(Incident i) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", i.getId());
		m.put("title", i.getTitle());
		m.put("service", i.getService());
		m.put("severity", i.getSeverity());
		m.put("status", i.getStatus());
		m.put("owner", i.getOwner());
		m.put("rule", i.getRuleName());
		m.put("openedAt", ImpactService.epoch(i.getOpenedAt()));
		m.put("acknowledgedAt", i.getAcknowledgedAt() == null ? null : ImpactService.epoch(i.getAcknowledgedAt()));
		m.put("acknowledgedBy", i.getAcknowledgedBy());
		m.put("resolvedAt", i.getResolvedAt() == null ? null : ImpactService.epoch(i.getResolvedAt()));
		m.put("resolvedBy", i.getResolvedBy());
		m.put("lastBreachAt", i.getLastBreachAt() == null ? null : ImpactService.epoch(i.getLastBreachAt()));
		m.put("peakErrorRate", i.getPeakErrorRate());
		LocalDateTime end = i.getResolvedAt() != null ? i.getResolvedAt() : LocalDateTime.now();
		m.put("durationMinutes", Math.max(0, Duration.between(i.getOpenedAt(), end).toSeconds()) / 60.0);
		return m;
	}

	private Incident get(long id) {
		return incidents.findById(id).orElseThrow(() -> new NotFound("incident " + id));
	}

	private IncidentEvent addEvent(Incident i, LocalDateTime at, String type, String actor, String text) {
		return events.save(new IncidentEvent(i.getId(), at, type, actor, text));
	}

	private void changed(Incident i, String change) {
		Map<String, Object> m = dto(i);
		m.put("change", change);
		broadcaster.publish("incident", m);
	}

	private static int rank(String severity) {
		return SEVERITIES.indexOf(severity);
	}

	private static double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}

	private static String hhmm(LocalDateTime t) {
		return String.format("%02d:%02d", t.getHour(), t.getMinute());
	}

	private static String blankTo(String s, String fallback) {
		return s == null || s.isBlank() ? fallback : s.trim();
	}

	private static String truncate(String s, int max) {
		return s == null ? null : s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}

	/** Thrown for unknown incident or rule ids; mapped to 404. */
	public static class NotFound extends RuntimeException {
		public NotFound(String what) {
			super("Not found: " + what);
		}
	}
}
