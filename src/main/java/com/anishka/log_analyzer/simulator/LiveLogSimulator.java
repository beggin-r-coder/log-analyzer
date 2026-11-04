package com.anishka.log_analyzer.simulator;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.anishka.log_analyzer.parser.ParsedLog;
import com.anishka.log_analyzer.parser.UniversalLogParser;
import com.anishka.log_analyzer.repository.LogRecordRepository;
import com.anishka.log_analyzer.service.LogIngestionService;

/**
 * Feeds the database with BFSI logs: back-fills history on first start, then
 * streams new events every few seconds so the dashboard is genuinely live.
 * Every generated line goes through the same {@link UniversalLogParser} as
 * externally ingested logs.
 */
@Component
public class LiveLogSimulator {

	private static final Logger log = LoggerFactory.getLogger(LiveLogSimulator.class);

	/** Relative traffic per hour of day for a retail bank (local business time). */
	private static final double[] HOURLY_TRAFFIC = { 0.25, 0.15, 0.12, 0.10, 0.12, 0.20, 0.35, 0.55, 0.80, 1.10,
			1.45, 1.60, 1.50, 1.40, 1.45, 1.40, 1.30, 1.35, 1.50, 1.55, 1.40, 1.10, 0.75, 0.50 };

	record Incident(String service, LocalDateTime start, LocalDateTime end) {
		boolean covers(LocalDateTime t) {
			return !t.isBefore(start) && t.isBefore(end);
		}
	}

	private final BfsiLogGenerator generator;
	private final UniversalLogParser parser;
	private final LogIngestionService ingestion;
	private final LogRecordRepository repository;
	private final Random rnd = new Random();

	private final boolean enabled;
	private final int seedDays;
	private final double avgPerMinute;
	private final long intervalMs;
	private final int retentionDays;
	private final ZoneId businessZone;

	private volatile boolean running;
	private volatile Incident currentIncident;
	private volatile boolean seeding;

	public LiveLogSimulator(BfsiLogGenerator generator, UniversalLogParser parser, LogIngestionService ingestion,
			LogRecordRepository repository, @Value("${logs.simulator.enabled:true}") boolean enabled,
			@Value("${logs.simulator.seed-days:7}") int seedDays,
			@Value("${logs.simulator.avg-per-minute:12}") double avgPerMinute,
			@Value("${logs.simulator.interval-ms:2000}") long intervalMs,
			@Value("${logs.retention-days:7}") int retentionDays,
			@Value("${logs.simulator.business-zone:Asia/Kolkata}") String businessZone) {
		this.generator = generator;
		this.parser = parser;
		this.ingestion = ingestion;
		this.repository = repository;
		this.enabled = enabled;
		this.seedDays = seedDays;
		this.avgPerMinute = avgPerMinute;
		this.intervalMs = intervalMs;
		this.retentionDays = retentionDays;
		this.businessZone = ZoneId.of(businessZone);
		this.running = enabled;
	}

	// =====================================================================
	// HISTORY BACK-FILL
	// =====================================================================

	@EventListener(ApplicationReadyEvent.class)
	public void seedIfEmpty() {
		if (!enabled || seedDays <= 0 || repository.count() > 0) {
			return;
		}
		seeding = true;
		long started = System.currentTimeMillis();
		LocalDateTime end = LocalDateTime.now();
		LocalDateTime start = end.minusDays(seedDays).truncatedTo(ChronoUnit.MINUTES);
		List<Incident> incidents = planHistoricIncidents(start, end);
		log.info("Seeding {} days of BFSI logs with {} historic incidents...", seedDays, incidents.size());

		List<ParsedLog> batch = new ArrayList<>(6000);
		long total = 0;
		for (LocalDateTime minute = start; minute.isBefore(end); minute = minute.plusMinutes(1)) {
			final LocalDateTime m = minute;
			Incident inc = incidents.stream().filter(i -> i.covers(m)).findFirst().orElse(null);
			int n = poisson(avgPerMinute * trafficWeight(minute));
			List<LocalDateTime> stamps = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				stamps.add(minute.plusNanos(rnd.nextInt(60_000) * 1_000_000L));
			}
			stamps.sort(Comparator.naturalOrder());
			for (LocalDateTime ts : stamps) {
				if (ts.isAfter(end)) {
					break;
				}
				BfsiLogGenerator.GeneratedLog g = generator.next(ts, rnd, inc == null ? null : inc.service());
				batch.addAll(parser.parse(g.raw(), g.service()));
			}
			if (batch.size() >= 5000) {
				total += flush(batch);
			}
		}
		total += flush(batch);
		seeding = false;
		log.info("Seeded {} log records in {} ms", total, System.currentTimeMillis() - started);
	}

	private List<Incident> planHistoricIncidents(LocalDateTime start, LocalDateTime end) {
		List<String> services = generator.serviceNames();
		List<Incident> out = new ArrayList<>();
		for (LocalDateTime h = start; h.isBefore(end.minusHours(3)); h = h.plusHours(1)) {
			if (rnd.nextDouble() < 0.07) {
				LocalDateTime s = h.plusMinutes(rnd.nextInt(60));
				out.add(new Incident(services.get(rnd.nextInt(services.size())), s,
						s.plusMinutes(4 + rnd.nextInt(18))));
			}
		}
		// two guaranteed, recent, recognisable incidents for the demo
		LocalDateTime upi = end.minusMinutes(150);
		out.add(new Incident("upi-switch", upi, upi.plusMinutes(14)));
		LocalDateTime cbs = end.minusHours(27);
		out.add(new Incident("core-banking", cbs, cbs.plusMinutes(22)));
		return out;
	}

	// =====================================================================
	// LIVE STREAM
	// =====================================================================

	@Scheduled(fixedDelayString = "${logs.simulator.interval-ms:2000}", initialDelay = 3000)
	public void tick() {
		if (!running || seeding) {
			return;
		}
		LocalDateTime now = LocalDateTime.now();
		updateIncident(now);
		Incident inc = currentIncident;

		int n = poisson(avgPerMinute * trafficWeight(now) * intervalMs / 60_000.0 * (inc != null ? 1.3 : 1.0));
		List<LocalDateTime> stamps = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			stamps.add(now.minusNanos((long) (rnd.nextDouble() * intervalMs) * 1_000_000L));
		}
		stamps.sort(Comparator.naturalOrder());
		List<ParsedLog> batch = new ArrayList<>();
		for (LocalDateTime ts : stamps) {
			BfsiLogGenerator.GeneratedLog g = generator.next(ts, rnd, inc == null ? null : inc.service());
			batch.addAll(parser.parse(g.raw(), g.service()));
		}
		flush(batch);
	}

	private void updateIncident(LocalDateTime now) {
		Incident inc = currentIncident;
		if (inc != null && !inc.covers(now)) {
			log.info("Simulated incident on {} resolved", inc.service());
			currentIncident = null;
		}
		// on average one incident every ~90 minutes, each lasting 3–10 minutes
		if (currentIncident == null && rnd.nextDouble() < intervalMs / (90 * 60_000.0)) {
			List<String> services = generator.serviceNames();
			String svc = services.get(rnd.nextInt(services.size()));
			currentIncident = new Incident(svc, now, now.plusMinutes(3 + rnd.nextInt(8)));
			log.info("Simulated incident started on {}", svc);
		}
	}

	/** Starts an incident on demand (used by the dashboard demo control). */
	public Incident triggerIncident(String service, int minutes) {
		String svc = service != null && generator.serviceNames().contains(service) ? service
				: generator.serviceNames().get(rnd.nextInt(generator.serviceNames().size()));
		LocalDateTime now = LocalDateTime.now();
		currentIncident = new Incident(svc, now, now.plusMinutes(Math.max(1, Math.min(minutes, 30))));
		log.info("Incident manually triggered on {}", svc);
		return currentIncident;
	}

	@Scheduled(cron = "0 15 * * * *")
	public void purgeExpired() {
		int deleted = repository.deleteOlderThan(LocalDateTime.now().minusDays(retentionDays));
		if (deleted > 0) {
			log.info("Retention: purged {} log records older than {} days", deleted, retentionDays);
		}
	}

	// =====================================================================
	// STATE
	// =====================================================================

	public void setRunning(boolean running) {
		this.running = running;
	}

	public Map<String, Object> status() {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("running", running);
		s.put("seeding", seeding);
		s.put("intervalMs", intervalMs);
		s.put("avgPerMinute", avgPerMinute);
		Incident inc = currentIncident;
		if (inc != null && inc.covers(LocalDateTime.now())) {
			s.put("incidentService", inc.service());
			s.put("incidentEndsInSec", Duration.between(LocalDateTime.now(), inc.end()).toSeconds());
		}
		s.put("services", generator.serviceNames());
		return s;
	}

	private long flush(List<ParsedLog> batch) {
		if (batch.isEmpty()) {
			return 0;
		}
		int n = batch.size();
		ingestion.save(batch);
		batch.clear();
		return n;
	}

	private double trafficWeight(LocalDateTime serverLocal) {
		ZonedDateTime biz = serverLocal.atZone(ZoneId.systemDefault()).withZoneSameInstant(businessZone);
		double w = HOURLY_TRAFFIC[biz.getHour()];
		DayOfWeek d = biz.getDayOfWeek();
		if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) {
			w *= 0.7;
		}
		return w;
	}

	private int poisson(double lambda) {
		if (lambda <= 0) {
			return 0;
		}
		double l = Math.exp(-lambda), p = 1;
		int k = 0;
		do {
			k++;
			p *= rnd.nextDouble();
		} while (p > l);
		return k - 1;
	}
}
