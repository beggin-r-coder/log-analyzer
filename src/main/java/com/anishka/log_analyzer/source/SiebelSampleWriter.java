package com.anishka.log_analyzer.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.anishka.log_analyzer.simulator.BfsiLogGenerator;

/**
 * Writes realistic Oracle Siebel CRM component logs into a folder, the way a Siebel server does: one file per
 * component task with the standard header line, tab-separated events, multi-line SQL and occasional outages.
 * Used for "Try with sample Siebel logs", so the folder source can be demonstrated without access to a real
 * Siebel server. It only writes to folders of sources marked as samples.
 */
@Component
public class SiebelSampleWriter {

	private static final Logger log = LoggerFactory.getLogger(SiebelSampleWriter.class);
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final String[] COMPONENTS = { "SCCObjMgr_enu", "EAIObjMgr_enu", "WfProcMgr" };
	private static final double[] WEIGHT = { .5, .3, .2 };
	private static final long ROTATE_BYTES = 8L * 1024 * 1024;
	private static final String NL = System.lineSeparator();

	private final BfsiLogGenerator generator;
	private final LogSourceRepository sources;
	private final Random rnd = new Random();
	private final Map<String, Path> currentFile = new HashMap<>();
	private String incidentComponent;
	private LocalDateTime incidentUntil;

	public SiebelSampleWriter(BfsiLogGenerator generator, LogSourceRepository sources) {
		this.generator = generator;
		this.sources = sources;
	}

	@Scheduled(fixedDelayString = "${app.sources.sample-ms:2000}", initialDelayString = "${app.sources.initial-delay-ms:1500}")
	public void tick() {
		for (LogSource s : sources.findByDemoTrueAndEnabledTrue()) {
			try {
				write(Path.of(s.getPath()), LocalDateTime.now(), 2);
			} catch (IOException e) {
				log.warn("Sample Siebel logs could not be written to {}: {}", s.getPath(), e.toString());
			}
		}
	}

	/** Creates the folder and its component logs, back-filled with the last {@code hours} of activity. */
	public synchronized void start(Path folder, int hours) throws IOException {
		Files.createDirectories(folder);
		LocalDateTime now = LocalDateTime.now();
		for (String comp : COMPONENTS) {
			Path f = open(folder, comp, now.minusHours(hours));
			List<String> lines = new ArrayList<>();
			for (LocalDateTime t = now.minusHours(hours); t.isBefore(now); t = t.plusSeconds(20 + rnd.nextInt(40))) {
				boolean outage = rnd.nextDouble() < .01;
				int n = outage ? 6 : 1;
				for (int i = 0; i < n; i++) lines.add(event(comp, t.plusSeconds(i), outage));
			}
			append(f, lines);
		}
	}

	/** Appends the activity of one interval of {@code seconds}. */
	synchronized void write(Path folder, LocalDateTime now, int seconds) throws IOException {
		if (!Files.isDirectory(folder) || currentFile.isEmpty() || !Files.exists(currentFile.values().iterator().next())) {
			start(folder, 0);
		}
		if (incidentUntil != null && now.isAfter(incidentUntil)) {
			incidentComponent = null;
			incidentUntil = null;
		}
		if (incidentComponent == null && rnd.nextDouble() < seconds / 420.0) { // about one outage every 7 minutes
			incidentComponent = COMPONENTS[rnd.nextInt(COMPONENTS.length)];
			incidentUntil = now.plusSeconds(90 + rnd.nextInt(150));
		}
		int events = 3 + rnd.nextInt(6);
		Map<String, List<String>> out = new HashMap<>();
		for (int i = 0; i < events; i++) {
			String comp = pick();
			boolean outage = comp.equals(incidentComponent);
			LocalDateTime t = now.minusNanos((long) (rnd.nextDouble() * seconds * 1e9));
			out.computeIfAbsent(comp, k -> new ArrayList<>()).add(event(comp, t, outage));
			if (outage) out.get(comp).add(event(comp, t.plusNanos(1_000_000), true)); // failures come with retries
		}
		if (rnd.nextDouble() < .15) {
			out.computeIfAbsent(COMPONENTS[0], k -> new ArrayList<>()).add(sql(now));
		}
		for (Map.Entry<String, List<String>> e : out.entrySet()) {
			Path f = currentFile.get(e.getKey());
			if (f == null || !Files.exists(f) || Files.size(f) > ROTATE_BYTES) {
				f = open(folder, e.getKey(), now);
			}
			append(f, e.getValue());
		}
	}

	private String event(String comp, LocalDateTime t, boolean outage) {
		return generator.nextFor(t, rnd, comp, outage).raw();
	}

	/** A multi-line SQL statement event, as Siebel writes with SQL tracing on. */
	private String sql(LocalDateTime t) {
		String sarm = String.format("%016x:0", rnd.nextLong());
		return "SQLParseAndExecute\tStatement\t4\t" + sarm + "\t" + t.format(TS) + "\tSELECT statement with ID: " + String.format("%08X", rnd.nextInt()) + NL
				+ "  SELECT /*+ ALL_ROWS */ T1.ROW_ID, T1.SR_NUM, T1.SR_STAT_ID, T1.OWNER_EMP_ID" + NL
				+ "  FROM SIEBEL.S_SRV_REQ T1" + NL
				+ "  WHERE T1.SR_STAT_ID = :1 AND T1.OWNER_EMP_ID = :2";
	}

	private String pick() {
		double x = rnd.nextDouble();
		for (int i = 0; i < COMPONENTS.length; i++) {
			if ((x -= WEIGHT[i]) < 0) return COMPONENTS[i];
		}
		return COMPONENTS[0];
	}

	/** A new log file for a component task, starting with the standard Siebel header line. */
	private Path open(Path folder, String comp, LocalDateTime t) throws IOException {
		int task = 2_000_000 + rnd.nextInt(900_000);
		int idx = 20 + java.util.Arrays.asList(COMPONENTS).indexOf(comp);
		Path f = folder.resolve(comp + "_" + String.format("%04d", idx) + "_" + task + ".log");
		ZoneOffset off = ZoneId.systemDefault().getRules().getOffset(t.atZone(ZoneId.systemDefault()).toInstant());
		String tz = off.getId().equals("Z") ? "+0000" : off.getId().replace(":", "");
		String header = "2021 " + t.format(TS) + " 0000-00-00 00:00:00 " + tz + " 00000000 001 003f 0001 09 " + comp + " " + task
				+ " " + (3000 + rnd.nextInt(25000)) + " " + (1000 + rnd.nextInt(9000)) + " " + f.toAbsolutePath() + " 8.1.1.11 [23030] ENU";
		Files.writeString(f, header + NL, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		currentFile.put(comp, f);
		return f;
	}

	private static void append(Path f, List<String> lines) throws IOException {
		if (lines.isEmpty()) return;
		Files.writeString(f, String.join(NL, lines) + NL, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
	}
}
