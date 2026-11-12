package com.anishka.log_analyzer.incident;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.anishka.log_analyzer.entity.AlertRule;
import com.anishka.log_analyzer.entity.Incident;
import com.anishka.log_analyzer.entity.LogRecord;
import com.anishka.log_analyzer.repository.IncidentEventRepository;
import com.anishka.log_analyzer.repository.IncidentRepository;
import com.anishka.log_analyzer.repository.LogRecordRepository;

/** Alert rules → incident → acknowledge → resolve, plus business impact and SLA maths. */
@SpringBootTest
class IncidentWorkflowTest {

	@Autowired IncidentService incidents;
	@Autowired ImpactService impact;
	@Autowired LogRecordRepository logs;
	@Autowired IncidentRepository incidentRepo;
	@Autowired IncidentEventRepository eventRepo;

	@BeforeEach
	void clean() {
		eventRepo.deleteAll();
		incidentRepo.deleteAll();
		logs.deleteAll();
	}

	private static LogRecord rec(LocalDateTime t, String service, String level, String message) {
		LogRecord r = new LogRecord();
		r.setTimestamp(t);
		r.setLevel(level);
		r.setLevelRank(switch (level) { case "FATAL" -> 5; case "ERROR" -> 4; case "WARN" -> 3; default -> 2; });
		r.setService(service);
		r.setMessage(message);
		r.setErrorCategory("ERROR".equals(level) ? "Payment Switch Timeout" : null);
		r.setSourceFormat("JSON");
		r.setRawLine(message);
		r.setIngestedAt(t);
		return r;
	}

	/** {@code ok} successful and {@code failed} failed UPI payments, spread over the last few minutes. */
	private void upiTraffic(LocalDateTime now, int ok, int failed) {
		List<LogRecord> batch = new ArrayList<>();
		for (int i = 0; i < ok; i++) {
			batch.add(rec(now.minusSeconds(10 + i * 7L), "upi-switch", "INFO",
					"UPI collect request NOVA" + (10000000000000L + i) + " from a****0" + (i % 10) + "@okaxis amount INR 2,000.00 SUCCESS"));
		}
		for (int i = 0; i < failed; i++) {
			// two attempts per customer: same VPA, different transaction ids
			batch.add(rec(now.minusSeconds(5 + i * 11L), "upi-switch", "ERROR",
					"NPCI switch timeout for NOVA" + (20000000000000L + i) + " from b****" + (10 + i / 2) + "@ybl amount INR 1,000.00 after 30000ms"));
		}
		logs.saveAll(batch);
	}

	@Test
	void ruleBreachOpensIncidentThatPeopleCanWork() {
		LocalDateTime now = LocalDateTime.now();
		upiTraffic(now, 20, 12);

		incidents.evaluate(now);
		List<Incident> open = incidentRepo.findAll();
		assertEquals(1, open.size(), "one incident per service");
		Incident i = open.get(0);
		assertEquals("upi-switch", i.getService());
		assertEquals("CRITICAL", i.getSeverity(), "the UPI-specific rule outranks the generic error-rate rule");
		assertEquals("payments-upi", i.getOwner());
		assertEquals(Incident.OPEN, i.getStatus());

		// evaluating again while still breaching keeps the same incident
		incidents.evaluate(now.plusSeconds(20));
		assertEquals(1, incidentRepo.count());

		incidents.acknowledge(i.getId(), "priya");
		incidents.note(i.getId(), "priya", "NPCI confirmed degradation");
		incidents.resolve(i.getId(), "priya", "Failed over to the secondary NPCI link");

		Map<String, Object> d = incidents.detail(i.getId(), impact);
		assertEquals(Incident.RESOLVED, d.get("status"));
		assertEquals("priya", d.get("acknowledgedBy"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> timeline = (List<Map<String, Object>>) d.get("timeline");
		assertEquals(List.of("OPENED", "ACKNOWLEDGED", "NOTE", "RESOLVED"), timeline.stream().map(e -> e.get("type")).toList());
		assertNotNull(d.get("impact"));
	}

	@Test
	void incidentAutoResolvesAfterRecovery() {
		LocalDateTime t0 = LocalDateTime.now().minusMinutes(30);
		upiTraffic(t0, 10, 12);
		incidents.evaluate(t0);
		assertEquals(1, incidentRepo.count());
		// half an hour later the window is clean
		incidents.evaluate(t0.plusMinutes(IncidentService.RECOVERY_MINUTES + 5));
		Incident i = incidentRepo.findAll().get(0);
		assertEquals(Incident.RESOLVED, i.getStatus());
		assertEquals("auto-recovery", i.getResolvedBy());
	}

	@Test
	void historyIsReconstructedFromStoredLogs() {
		LocalDateTime now = LocalDateTime.now();
		upiTraffic(now.minusHours(3), 20, 12);
		upiTraffic(now, 30, 0);
		int n = incidents.backfill(now.minusHours(4), now);
		assertEquals(1, n);
		Incident i = incidentRepo.findAll().get(0);
		assertEquals(Incident.RESOLVED, i.getStatus());
		assertNotNull(i.getAcknowledgedAt());
		assertTrue(i.getAcknowledgedAt().isBefore(i.getResolvedAt()));
	}

	@Test
	void businessImpactCountsMoneyCustomersAndSla() {
		LocalDateTime now = LocalDateTime.now();
		upiTraffic(now, 20, 12);
		Map<String, Object> m = impact.summary("1h");
		assertEquals(12L, m.get("failedTransactions"));
		assertEquals(12_000.0, (Double) m.get("valueAtRisk"), 0.01);
		assertEquals(6L, m.get("customersAffected"), "retries by the same customer are counted once");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> sla = (List<Map<String, Object>>) m.get("sla");
		Map<String, Object> upi = sla.stream().filter(s -> "upi-switch".equals(s.get("service"))).findFirst().orElseThrow();
		assertEquals(99.5, upi.get("target"));
		assertTrue((Double) upi.get("availability") < 100);
	}

	@Test
	void ruleThresholds() {
		AlertRule rate = new AlertRule("r", "*", AlertRule.ERROR_RATE, 15, 5, 5, "HIGH");
		assertTrue(IncidentService.breaches(rate, 20, 5));
		assertTrue(!IncidentService.breaches(rate, 100, 5), "5% is under the 15% threshold");
		assertTrue(!IncidentService.breaches(rate, 4, 4), "too few errors to alert");
		AlertRule count = new AlertRule("c", "upi-switch", AlertRule.ERROR_COUNT, 8, 5, 0, "CRITICAL");
		assertTrue(IncidentService.breaches(count, 1000, 8));
		assertTrue(count.appliesTo("upi-switch") && !count.appliesTo("card-auth"));
		assertEquals("₹4.20 L", ImpactService.inr(420_000));
		assertEquals("₹1.30 Cr", ImpactService.inr(13_000_000));
	}
}
