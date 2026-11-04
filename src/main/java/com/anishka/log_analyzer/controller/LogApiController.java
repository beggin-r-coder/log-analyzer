package com.anishka.log_analyzer.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.anishka.log_analyzer.dto.DashboardSummary;
import com.anishka.log_analyzer.dto.IngestResult;
import com.anishka.log_analyzer.dto.LogQuery;
import com.anishka.log_analyzer.dto.LogRecordDto;
import com.anishka.log_analyzer.dto.PageResponse;
import com.anishka.log_analyzer.entity.LogRecord;
import com.anishka.log_analyzer.parser.ParsedLog;
import com.anishka.log_analyzer.service.DashboardService;
import com.anishka.log_analyzer.service.LogIngestionService;
import com.anishka.log_analyzer.service.LogQueryService;
import com.anishka.log_analyzer.simulator.LiveLogSimulator;
import com.anishka.log_analyzer.stream.LogStreamBroadcaster;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class LogApiController {

	private static final int MAX_INGEST_CHARS = 5_000_000;

	private final DashboardService dashboard;
	private final LogQueryService queries;
	private final LogIngestionService ingestion;
	private final LiveLogSimulator simulator;
	private final LogStreamBroadcaster broadcaster;

	public LogApiController(DashboardService dashboard, LogQueryService queries, LogIngestionService ingestion,
			LiveLogSimulator simulator, LogStreamBroadcaster broadcaster) {
		this.broadcaster = broadcaster;
		this.dashboard = dashboard;
		this.queries = queries;
		this.ingestion = ingestion;
		this.simulator = simulator;
	}

	// ---------- dashboard ----------

	@GetMapping("/dashboard")
	public DashboardSummary dashboard(@RequestParam(defaultValue = "24h") String range,
			@RequestParam(required = false) Integer tz) {
		return dashboard.summary(range, tz);
	}

	// ---------- log explorer ----------

	@GetMapping("/logs")
	public PageResponse<LogRecordDto> logs(LogQuery query) {
		return queries.search(query);
	}

	/** Real-time feed: every newly stored log is pushed as a Server-Sent Event named "logs". */
	@GetMapping(value = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream() {
		return broadcaster.subscribe();
	}

	@GetMapping("/logs/{id}")
	public LogRecordDto log(@PathVariable long id) {
		return queries.byId(id);
	}

	@GetMapping("/logs/facets")
	public Map<String, Object> facets() {
		return dashboard.facets();
	}

	@GetMapping(value = "/logs/export", produces = "text/csv")
	public ResponseEntity<byte[]> export(LogQuery query) {
		StringBuilder csv = new StringBuilder("timestamp,level,service,host,category,trace_id,client_ip,message\n");
		for (LogRecordDto r : queries.export(query)) {
			csv.append(String.join(",", csvCell(r.timestampIso()), csvCell(r.level()), csvCell(r.service()),
					csvCell(r.host()), csvCell(r.category()), csvCell(r.traceId()), csvCell(r.clientIp()),
					csvCell(r.message()))).append('\n');
		}
		String name = "logs-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv";
		return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
				.contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
				.body(csv.toString().getBytes(StandardCharsets.UTF_8));
	}

	// ---------- ingestion: any log format ----------

	/** Parse and store raw log text, e.g. {@code curl --data-binary @app.log -H 'Content-Type: text/plain' ...}. */
	@PostMapping(value = "/logs/ingest", consumes = MediaType.ALL_VALUE)
	public IngestResult ingest(@RequestBody String body, @RequestParam(required = false) String service) {
		checkSize(body);
		List<LogRecord> saved = ingestion.ingest(body, blank(service));
		List<LogRecordDto> dtos = saved.stream().map(LogRecordDto::from).toList();
		return new IngestResult(dtos.size(), true, formatCounts(dtos), dtos.stream().limit(50).toList());
	}

	/** Parse only – shows how each line would be understood, without storing it. */
	@PostMapping(value = "/logs/parse", consumes = MediaType.ALL_VALUE)
	public IngestResult parse(@RequestBody String body, @RequestParam(required = false) String service) {
		checkSize(body);
		List<ParsedLog> parsed = ingestion.preview(body, blank(service));
		LocalDateTime now = LocalDateTime.now();
		List<LogRecordDto> dtos = parsed.stream().map(p -> LogRecordDto.from(ingestion.toPreviewRecord(p, now)))
				.toList();
		return new IngestResult(dtos.size(), false, formatCounts(dtos), dtos.stream().limit(200).toList());
	}

	// ---------- live simulator controls ----------

	@GetMapping("/simulator")
	public Map<String, Object> simulator() {
		return simulator.status();
	}

	@PostMapping("/simulator/pause")
	public Map<String, Object> pause() {
		simulator.setRunning(false);
		return simulator.status();
	}

	@PostMapping("/simulator/resume")
	public Map<String, Object> resume() {
		simulator.setRunning(true);
		return simulator.status();
	}

	@PostMapping("/simulator/incident")
	public Map<String, Object> incident(@RequestParam(required = false) String service,
			@RequestParam(defaultValue = "5") int minutes) {
		simulator.triggerIncident(blank(service), minutes);
		return simulator.status();
	}

	// ---------- helpers ----------

	private static Map<String, Long> formatCounts(List<LogRecordDto> dtos) {
		return dtos.stream().collect(Collectors.groupingBy(d -> d.formatLabel() == null ? "?" : d.formatLabel(),
				TreeMap::new, Collectors.counting()));
	}

	private static void checkSize(String body) {
		if (body == null || body.isBlank()) {
			throw new IllegalArgumentException("Request body is empty – send raw log text.");
		}
		if (body.length() > MAX_INGEST_CHARS) {
			throw new IllegalArgumentException("Payload too large (max 5 MB per request).");
		}
	}

	private static String blank(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}

	private static String csvCell(String v) {
		if (v == null) {
			return "";
		}
		String s = v.replace("\r", " ").replace("\n", " ⏎ ");
		// neutralise spreadsheet formula injection
		if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
			s = "'" + s;
		}
		return "\"" + s.replace("\"", "\"\"") + "\"";
	}
}
