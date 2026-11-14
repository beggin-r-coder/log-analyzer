package com.anishka.log_analyzer.source;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.anishka.log_analyzer.parser.ParsedLog;
import com.anishka.log_analyzer.parser.UniversalLogParser;

/** Creates, edits and tests log sources, and reports their status for the Log sources screen. */
@Service
public class LogSourceService {

	private final LogSourceRepository sources;
	private final SourceFileStateRepository fileStates;
	private final FolderTailer tailer;
	private final DatabasePoller poller;
	private final SiebelSampleWriter sampleWriter;
	private final SecretBox secrets;
	private final UniversalLogParser parser;
	private final Path sampleDir;

	public LogSourceService(LogSourceRepository sources, SourceFileStateRepository fileStates, FolderTailer tailer,
			DatabasePoller poller, SiebelSampleWriter sampleWriter, SecretBox secrets, UniversalLogParser parser,
			@Value("${app.sources.sample-dir:}") String sampleDir, @Value("${DATA_DIR:./data}") String dataDir) {
		this.sources = sources;
		this.fileStates = fileStates;
		this.tailer = tailer;
		this.poller = poller;
		this.sampleWriter = sampleWriter;
		this.secrets = secrets;
		this.parser = parser;
		Path data = Path.of(dataDir).toAbsolutePath().normalize();
		this.sampleDir = sampleDir == null || sampleDir.isBlank()
				? (data.getParent() == null ? data : data.getParent()).resolve("sample-siebel-logs")
				: Path.of(sampleDir).toAbsolutePath();
	}

	public List<Map<String, Object>> list() {
		return sources.findAll(org.springframework.data.domain.Sort.by("id")).stream().map(LogSourceService::dto).toList();
	}

	public Map<String, Object> get(long id) {
		return dto(find(id));
	}

	public Map<String, Object> create(SourceForm f) {
		LogSource s = new LogSource();
		apply(s, f, true);
		validate(s);
		return dto(sources.save(s));
	}

	public Map<String, Object> update(long id, SourceForm f) {
		LogSource s = find(id);
		boolean moved = changedLocation(s, f);
		apply(s, f, false);
		validate(s);
		if (moved) { // a different folder or query: start reading it afresh
			fileStates.deleteBySource(id);
			s.setWatermarkId(null);
			s.setWatermarkTs(null);
			s.setFilesTracked(0);
		}
		s.setLastError(null);
		return dto(sources.save(s));
	}

	public void delete(long id) {
		LogSource s = find(id);
		fileStates.deleteBySource(id);
		sources.delete(s);
		if (s.isDemo()) {
			try (Stream<Path> files = Files.list(Path.of(s.getPath()))) {
				files.filter(p -> p.getFileName().toString().endsWith(".log")).forEach(p -> p.toFile().delete());
			} catch (IOException e) {
				// sample folder already gone
			}
		}
	}

	public Map<String, Object> setEnabled(long id, boolean enabled) {
		LogSource s = find(id);
		s.setEnabled(enabled);
		return dto(sources.save(s));
	}

	/** Reads the source right now instead of waiting for the next pass. */
	public Map<String, Object> scan(long id) {
		LogSource s = find(id);
		int n = LogSource.FOLDER.equals(s.getType()) ? tailer.poll(s) : poller.poll(s);
		Map<String, Object> out = dto(find(id));
		out.put("lastScanEvents", n);
		return out;
	}

	/** One click: a folder of realistic Siebel logs that keeps growing, followed like any other folder. */
	public Map<String, Object> createSample() throws IOException {
		for (LogSource s : sources.findAll()) {
			if (s.isDemo()) {
				s.setEnabled(true);
				return dto(sources.save(s));
			}
		}
		sampleWriter.start(sampleDir, 3);
		LogSource s = new LogSource();
		s.setName("Sample Siebel logs");
		s.setType(LogSource.FOLDER);
		s.setPath(sampleDir.toString());
		s.setFilePattern("*.log");
		s.setBackfillDays(1);
		s.setDemo(true);
		s = sources.save(s);
		tailer.poll(s);
		return dto(find(s.getId()));
	}

	/** Checks a source before it is saved: lists matching files or connects to the database and shows rows. */
	public Map<String, Object> test(SourceForm f, Long existingId) {
		LogSource s = new LogSource();
		apply(s, f, true);
		String password = f.password();
		if ((password == null || password.isEmpty()) && existingId != null) {
			s.setPasswordEnc(find(existingId).getPasswordEnc());
			password = null;
		}
		Map<String, Object> out = new LinkedHashMap<>();
		try {
			if (LogSource.FOLDER.equals(s.getType())) {
				out.putAll(testFolder(s));
			} else {
				out.putAll(poller.preview(s, password));
			}
			out.put("ok", true);
		} catch (Exception e) {
			out.put("ok", false);
			out.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
		}
		return out;
	}

	public Map<String, Object> summary() {
		List<LogSource> all = sources.findAll();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("sources", all.size());
		m.put("enabled", all.stream().filter(LogSource::isEnabled).count());
		m.put("errors", all.stream().filter(s -> s.isEnabled() && s.getLastError() != null).count());
		m.put("events", all.stream().mapToLong(LogSource::getEventsIngested).sum());
		m.put("sampleDir", sampleDir.toString());
		return m;
	}

	// ------------------------------------------------------------------

	private Map<String, Object> testFolder(LogSource s) throws IOException {
		Path dir = Path.of(s.getPath() == null ? "" : s.getPath().trim());
		if (!Files.isDirectory(dir)) {
			throw new IOException("Folder not found or not reachable from this computer: " + dir);
		}
		List<Path> found = new ArrayList<>(FolderTailer.list(dir, s));
		found.sort(Comparator.comparing((Path p) -> p.toFile().lastModified()).reversed());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("files", found.size());
		long total = 0;
		List<Map<String, Object>> newest = new ArrayList<>();
		for (Path p : found) {
			total += p.toFile().length();
			if (newest.size() < 8) {
				Map<String, Object> f = new LinkedHashMap<>();
				f.put("name", dir.relativize(p).toString());
				f.put("size", p.toFile().length());
				f.put("modified", p.toFile().lastModified());
				newest.add(f);
			}
		}
		out.put("totalBytes", total);
		out.put("newest", newest);
		List<Map<String, Object>> preview = new ArrayList<>();
		Map<String, Integer> formats = new TreeMap<>();
		if (!found.isEmpty()) {
			Path p = found.get(0);
			String service = s.getDefaultService() == null || s.getDefaultService().isBlank() ? FolderTailer.serviceFromFile(p) : s.getDefaultService();
			List<ParsedLog> parsed = parser.parse(head(p, 64 * 1024), service);
			for (ParsedLog e : parsed) formats.merge(e.getFormat().getLabel(), 1, Integer::sum);
			for (ParsedLog e : parsed.subList(0, Math.min(25, parsed.size()))) {
				Map<String, Object> r = new LinkedHashMap<>();
				r.put("format", e.getFormat().getLabel());
				r.put("time", e.getTimestamp() == null ? null : e.getTimestamp().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
				r.put("level", e.getLevel());
				r.put("service", e.getService());
				String m = e.getMessage() == null ? "" : e.getMessage().split("\\n", 2)[0];
				r.put("message", m.length() > 200 ? m.substring(0, 199) + "…" : m);
				preview.add(r);
			}
			out.put("previewFile", dir.relativize(p).toString());
		}
		out.put("formats", formats);
		out.put("preview", preview);
		return out;
	}

	private static String head(Path p, int bytes) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
			byte[] b = new byte[(int) Math.min(bytes, raf.length())];
			raf.readFully(b);
			String s = new String(b, StandardCharsets.UTF_8);
			int nl = s.lastIndexOf('\n');
			return nl > 0 && b.length == bytes ? s.substring(0, nl) : s;
		}
	}

	private void apply(LogSource s, SourceForm f, boolean creating) {
		String type = f.type() == null ? LogSource.FOLDER : f.type().toUpperCase(Locale.ROOT);
		if (!LogSource.FOLDER.equals(type) && !LogSource.DATABASE.equals(type)) {
			throw new IllegalArgumentException("Unknown source type: " + f.type());
		}
		s.setType(type);
		s.setName(blank(f.name()) ? (LogSource.FOLDER.equals(type) ? "Log folder" : "Log database") : f.name().trim());
		if (f.enabled() != null) s.setEnabled(f.enabled());
		s.setPath(trim(f.path()));
		s.setFilePattern(blank(f.filePattern()) ? "*.log" : f.filePattern().trim());
		s.setRecursive(Boolean.TRUE.equals(f.recursive()));
		s.setBackfillDays(f.backfillDays() == null ? 1 : Math.max(0, Math.min(f.backfillDays(), 30)));
		s.setDefaultService(trim(f.defaultService()));
		s.setDbType(trim(f.dbType()));
		s.setHost(trim(f.host()));
		s.setPort(f.port());
		s.setDatabaseName(trim(f.databaseName()));
		s.setUsername(trim(f.username()));
		if (!blank(f.password())) {
			s.setPasswordEnc(secrets.encrypt(f.password()));
		}
		s.setJdbcUrl(trim(f.jdbcUrl()));
		s.setQueryText(trim(f.queryText()));
		s.setTsColumn(trim(f.tsColumn()));
		s.setLevelColumn(trim(f.levelColumn()));
		s.setServiceColumn(trim(f.serviceColumn()));
		s.setMessageColumn(trim(f.messageColumn()));
		s.setTraceColumn(trim(f.traceColumn()));
		s.setHostColumn(trim(f.hostColumn()));
		s.setRawColumn(trim(f.rawColumn()));
		s.setIdColumn(trim(f.idColumn()));
		s.setPollSeconds(f.pollSeconds() == null ? 15 : Math.max(5, Math.min(f.pollSeconds(), 3600)));
	}

	/** Checks a source is complete before it is saved (testing works on incomplete drafts). */
	private static void validate(LogSource s) {
		if (LogSource.FOLDER.equals(s.getType())) {
			if (blank(s.getPath())) throw new IllegalArgumentException("Choose the folder that contains the log files");
			return;
		}
		if (blank(s.getQueryText())) throw new IllegalArgumentException("Enter a table name or a SELECT query");
		if (blank(s.getTsColumn())) throw new IllegalArgumentException("Map the timestamp column");
		if (blank(s.getMessageColumn()) && blank(s.getRawColumn())) throw new IllegalArgumentException("Map a message column (or a raw log line column)");
	}

	private static boolean changedLocation(LogSource s, SourceForm f) {
		return !eq(s.getPath(), f.path()) || !eq(s.getFilePattern(), f.filePattern()) || !eq(s.getQueryText(), f.queryText())
				|| !eq(s.getJdbcUrl(), f.jdbcUrl()) || !eq(s.getHost(), f.host()) || !eq(s.getDatabaseName(), f.databaseName());
	}

	private LogSource find(long id) {
		return sources.findById(id).orElseThrow(() -> new IllegalArgumentException("No log source " + id));
	}

	static Map<String, Object> dto(LogSource s) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", s.getId());
		m.put("name", s.getName());
		m.put("type", s.getType());
		m.put("enabled", s.isEnabled());
		m.put("demo", s.isDemo());
		m.put("status", !s.isEnabled() ? "PAUSED" : s.getLastError() != null ? "ERROR" : s.getLastSuccessAt() == null ? "STARTING" : "RUNNING");
		m.put("path", s.getPath());
		m.put("filePattern", s.getFilePattern());
		m.put("recursive", s.isRecursive());
		m.put("backfillDays", s.getBackfillDays());
		m.put("defaultService", s.getDefaultService());
		m.put("dbType", s.getDbType());
		m.put("host", s.getHost());
		m.put("port", s.getPort());
		m.put("databaseName", s.getDatabaseName());
		m.put("username", s.getUsername());
		m.put("hasPassword", s.getPasswordEnc() != null);
		m.put("jdbcUrl", s.getJdbcUrl());
		m.put("queryText", s.getQueryText());
		m.put("tsColumn", s.getTsColumn());
		m.put("levelColumn", s.getLevelColumn());
		m.put("serviceColumn", s.getServiceColumn());
		m.put("messageColumn", s.getMessageColumn());
		m.put("traceColumn", s.getTraceColumn());
		m.put("hostColumn", s.getHostColumn());
		m.put("rawColumn", s.getRawColumn());
		m.put("idColumn", s.getIdColumn());
		m.put("pollSeconds", s.getPollSeconds());
		m.put("location", LogSource.FOLDER.equals(s.getType()) ? s.getPath() + "  (" + s.getFilePattern() + ")" : safeUrl(s));
		m.put("lastRunAt", epoch(s.getLastRunAt()));
		m.put("lastSuccessAt", epoch(s.getLastSuccessAt()));
		m.put("lastError", s.getLastError());
		m.put("eventsIngested", s.getEventsIngested());
		m.put("filesTracked", s.getFilesTracked());
		return m;
	}

	private static String safeUrl(LogSource s) {
		try {
			return DatabasePoller.url(s).replaceAll("(?i)(password=)[^;&]*", "$1***");
		} catch (RuntimeException e) {
			return "(not configured)";
		}
	}

	private static Long epoch(LocalDateTime t) {
		return t == null ? null : t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

	private static String trim(String s) {
		return blank(s) ? null : s.trim();
	}

	private static boolean eq(String a, String b) {
		return (blank(a) ? "" : a.trim()).equals(blank(b) ? "" : b.trim());
	}
}
