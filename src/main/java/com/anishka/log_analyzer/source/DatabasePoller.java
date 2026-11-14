package com.anishka.log_analyzer.source;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.anishka.log_analyzer.parser.LogFormat;
import com.anishka.log_analyzer.parser.ParsedLog;
import com.anishka.log_analyzer.parser.UniversalLogParser;
import com.anishka.log_analyzer.service.LogIngestionService;

/**
 * Reads log rows from an external database (SQL Server, Oracle, PostgreSQL, MySQL; H2 for testing), read-only.
 * Each pass fetches only rows newer than the last one seen (by an increasing id column, or by timestamp) and
 * maps the chosen columns to a log event. A column holding a complete raw log line is parsed like a file line.
 */
@Service
public class DatabasePoller {

	private static final Logger log = LoggerFactory.getLogger(DatabasePoller.class);
	static final int MAX_ROWS = 5000;
	private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*(\\.[A-Za-z_][A-Za-z0-9_$#]*){0,2}");

	private final LogSourceRepository sources;
	private final UniversalLogParser parser;
	private final LogIngestionService ingestion;
	private final SecretBox secrets;

	public DatabasePoller(LogSourceRepository sources, UniversalLogParser parser, LogIngestionService ingestion, SecretBox secrets) {
		this.sources = sources;
		this.parser = parser;
		this.ingestion = ingestion;
		this.secrets = secrets;
	}

	@Scheduled(fixedDelayString = "${app.sources.db-tick-ms:5000}", initialDelayString = "${app.sources.initial-delay-ms:4000}")
	public void pollAll() {
		LocalDateTime now = LocalDateTime.now();
		for (LogSource s : sources.findByTypeAndEnabledTrue(LogSource.DATABASE)) {
			if (s.getLastRunAt() == null || s.getLastRunAt().plusSeconds(Math.max(5, s.getPollSeconds())).isBefore(now)) {
				poll(s);
			}
		}
	}

	/** One pass: fetch new rows and store them. Returns the number of events stored. */
	public synchronized int poll(LogSource src) {
		LocalDateTime runAt = LocalDateTime.now();
		int stored = 0;
		String error = null;
		// continue from the latest watermark, not from a copy loaded before an earlier pass finished
		sources.findById(src.getId()).ifPresent(fresh -> {
			src.setWatermarkId(fresh.getWatermarkId());
			src.setWatermarkTs(fresh.getWatermarkTs());
		});
		try (Connection c = connect(src, null)) {
			boolean byId = notBlank(src.getIdColumn());
			String key = ident(byId ? src.getIdColumn() : src.getTsColumn(), "timestamp column");
			String sql = "SELECT * FROM " + from(src) + " WHERE " + key + " > ? ORDER BY " + key;
			try (PreparedStatement ps = c.prepareStatement(sql)) {
				ps.setMaxRows(MAX_ROWS);
				ps.setQueryTimeout(60);
				if (byId) {
					String last = src.getWatermarkId();
					if (last == null) {
						ps.setLong(1, Long.MIN_VALUE);
					} else if (last.matches("-?\\d+")) {
						ps.setLong(1, Long.parseLong(last));
					} else {
						ps.setString(1, last);
					}
				} else {
					LocalDateTime from = src.getWatermarkTs() != null ? src.getWatermarkTs()
							: LocalDateTime.now().minusDays(Math.max(0, src.getBackfillDays()));
					ps.setTimestamp(1, Timestamp.valueOf(from));
				}
				List<ParsedLog> batch = new ArrayList<>();
				try (ResultSet rs = ps.executeQuery()) {
					Map<String, Integer> cols = columns(rs.getMetaData());
					while (rs.next()) {
						batch.addAll(map(src, rs, cols));
						if (byId) {
							src.setWatermarkId(rs.getString(col(cols, src.getIdColumn())));
						} else {
							LocalDateTime t = timestamp(rs.getObject(col(cols, src.getTsColumn())));
							if (t != null) src.setWatermarkTs(t);
						}
					}
				}
				if (!batch.isEmpty()) {
					ingestion.save(batch);
					sources.recordWatermark(src.getId(), src.getWatermarkTs(), src.getWatermarkId());
				}
				stored = batch.size();
			}
		} catch (SQLException | RuntimeException e) {
			error = e.getMessage() == null ? e.toString() : e.getMessage();
			log.debug("Database source {} failed: {}", src.getName(), e.toString());
		}
		sources.recordRun(src.getId(), runAt, error == null ? LocalDateTime.now() : null,
				error == null ? null : error.length() > 1000 ? error.substring(0, 999) : error, stored, null);
		return stored;
	}

	/** Connects and returns the first rows of the table / query with a suggested column mapping. */
	public Map<String, Object> preview(LogSource src, String plainPassword) throws SQLException {
		try (Connection c = connect(src, plainPassword);
				PreparedStatement ps = c.prepareStatement("SELECT * FROM " + from(src))) {
			ps.setMaxRows(10);
			ps.setQueryTimeout(30);
			try (ResultSet rs = ps.executeQuery()) {
				ResultSetMetaData md = rs.getMetaData();
				List<String> names = new ArrayList<>();
				for (int i = 1; i <= md.getColumnCount(); i++) names.add(md.getColumnLabel(i));
				List<List<String>> rows = new ArrayList<>();
				while (rs.next()) {
					List<String> r = new ArrayList<>();
					for (int i = 1; i <= names.size(); i++) {
						Object o = rs.getObject(i);
						String v = o == null ? "" : String.valueOf(o);
						r.add(v.length() > 160 ? v.substring(0, 159) + "…" : v);
					}
					rows.add(r);
				}
				Map<String, Object> out = new LinkedHashMap<>();
				out.put("product", c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion());
				out.put("columns", names);
				out.put("rows", rows);
				out.put("suggested", suggest(names));
				return out;
			}
		}
	}

	// ------------------------------------------------------------------ mapping

	private List<ParsedLog> map(LogSource src, ResultSet rs, Map<String, Integer> cols) throws SQLException {
		String service = notBlank(src.getServiceColumn()) ? rs.getString(col(cols, src.getServiceColumn())) : null;
		if (service == null || service.isBlank()) {
			service = notBlank(src.getDefaultService()) ? src.getDefaultService() : src.getName();
		}
		if (notBlank(src.getRawColumn())) {
			String raw = rs.getString(col(cols, src.getRawColumn()));
			List<ParsedLog> parsed = parser.parse(raw, service);
			LocalDateTime t = notBlank(src.getTsColumn()) ? timestamp(rs.getObject(col(cols, src.getTsColumn()))) : null;
			if (t != null) parsed.forEach(p -> p.timestamp(t));
			return parsed;
		}
		ParsedLog p = new ParsedLog().format(LogFormat.DATABASE).service(service.length() > 64 ? service.substring(0, 64) : service);
		p.timestamp(notBlank(src.getTsColumn()) ? timestamp(rs.getObject(col(cols, src.getTsColumn()))) : null);
		String msg = notBlank(src.getMessageColumn()) ? rs.getString(col(cols, src.getMessageColumn())) : null;
		p.message(msg == null ? "" : msg);
		String level = notBlank(src.getLevelColumn()) ? rs.getString(col(cols, src.getLevelColumn())) : null;
		p.level(level(level, p.getMessage()));
		if (notBlank(src.getTraceColumn())) p.traceId(rs.getString(col(cols, src.getTraceColumn())));
		if (notBlank(src.getHostColumn())) p.host(rs.getString(col(cols, src.getHostColumn())));
		p.rawLine(p.getMessage());
		if (p.getTimestamp() == null) p.timestamp(LocalDateTime.now());
		return List.of(p);
	}

	/** Text levels (ERROR, warning, Sev 2…) or numeric Siebel-style severities 0-5. */
	static String level(String raw, String message) {
		if (raw != null && raw.strip().matches("[0-5]")) {
			return UniversalLogParser.siebelLevel("GenericLog", "Generic", Integer.parseInt(raw.strip()), message == null ? "" : message);
		}
		String l = UniversalLogParser.normalizeLevel(raw);
		return l != null ? l : "INFO";
	}

	static LocalDateTime timestamp(Object o) {
		if (o == null) return null;
		if (o instanceof Timestamp t) return t.toLocalDateTime();
		if (o instanceof LocalDateTime t) return t;
		if (o instanceof OffsetDateTime t) return t.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
		if (o instanceof java.util.Date d) return LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault());
		if (o instanceof Number n) return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(n.longValue() > 99_999_999_999L ? n.longValue() : n.longValue() * 1000), ZoneId.systemDefault());
		try {
			return LocalDateTime.parse(o.toString().strip().replace(' ', 'T').replaceAll("\\.\\d+$", ""));
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Guesses which column is which from common names (Siebel, log4j tables, application audit tables). */
	static Map<String, String> suggest(List<String> names) {
		Map<String, String> m = new LinkedHashMap<>();
		for (String n : names) {
			String l = n.toLowerCase(Locale.ROOT);
			if (!m.containsKey("tsColumn") && l.matches(".*(timestamp|time|date|created|logged|_dt|^ts$).*")) m.put("tsColumn", n);
			else if (!m.containsKey("levelColumn") && l.matches(".*(level|severity|^sev$|priority|loglevel).*")) m.put("levelColumn", n);
			else if (!m.containsKey("messageColumn") && l.matches(".*(message|^msg$|text|description|details|error_desc).*")) m.put("messageColumn", n);
			else if (!m.containsKey("serviceColumn") && l.matches(".*(component|service|application|^app$|module|source).*")) m.put("serviceColumn", n);
			else if (!m.containsKey("traceColumn") && l.matches(".*(sarm|trace|correlation|request_id|session).*")) m.put("traceColumn", n);
			else if (!m.containsKey("hostColumn") && l.matches(".*(host|server_name|machine|node).*")) m.put("hostColumn", n);
			else if (!m.containsKey("rawColumn") && l.matches(".*(raw|log_line|line_text).*")) m.put("rawColumn", n);
			else if (!m.containsKey("idColumn") && l.matches("(id|row_id|log_id|seq|sequence|.*_seq)")) m.put("idColumn", n);
		}
		return m;
	}

	// ------------------------------------------------------------------ connection

	Connection connect(LogSource src, String plainPassword) throws SQLException {
		String pw = plainPassword != null ? plainPassword : secrets.decrypt(src.getPasswordEnc());
		Properties props = new Properties();
		if (notBlank(src.getUsername())) props.setProperty("user", src.getUsername());
		if (pw != null) props.setProperty("password", pw);
		DriverManager.setLoginTimeout(15);
		Connection c = DriverManager.getConnection(url(src), props);
		try {
			c.setReadOnly(true);
		} catch (SQLException e) {
			// some drivers do not support the hint; the account should be read-only anyway
		}
		return c;
	}

	public static String url(LogSource s) {
		if (notBlank(s.getJdbcUrl())) return s.getJdbcUrl().trim();
		String type = s.getDbType() == null ? "" : s.getDbType().toUpperCase(Locale.ROOT);
		String host = notBlank(s.getHost()) ? s.getHost().trim() : "localhost";
		String db = s.getDatabaseName() == null ? "" : s.getDatabaseName().trim();
		return switch (type) {
		case "SQLSERVER" -> "jdbc:sqlserver://" + host + ":" + port(s, 1433) + (db.isEmpty() ? "" : ";databaseName=" + db)
				+ ";encrypt=true;trustServerCertificate=true;loginTimeout=15";
		case "ORACLE" -> "jdbc:oracle:thin:@//" + host + ":" + port(s, 1521) + "/" + db;
		case "POSTGRES" -> "jdbc:postgresql://" + host + ":" + port(s, 5432) + "/" + db;
		case "MYSQL" -> "jdbc:mysql://" + host + ":" + port(s, 3306) + "/" + db;
		case "H2" -> "jdbc:h2:" + db;
		default -> throw new IllegalArgumentException("Choose a database type or enter a JDBC URL");
		};
	}

	private static int port(LogSource s, int def) {
		return s.getPort() == null || s.getPort() <= 0 ? def : s.getPort();
	}

	/** The table/view name (validated) or the user's SELECT wrapped as a derived table. */
	static String from(LogSource s) {
		String q = s.getQueryText() == null ? "" : s.getQueryText().trim();
		if (q.endsWith(";")) q = q.substring(0, q.length() - 1).trim();
		String head = q.toLowerCase(Locale.ROOT);
		if (head.startsWith("select") || head.startsWith("with")) {
			return "(" + q + ") src";
		}
		return ident(q, "table name");
	}

	static String ident(String name, String what) {
		if (name == null || !IDENT.matcher(name.trim()).matches()) {
			throw new IllegalArgumentException("Invalid " + what + ": " + (name == null ? "(empty)" : name));
		}
		return name.trim();
	}

	private static Map<String, Integer> columns(ResultSetMetaData md) throws SQLException {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (int i = 1; i <= md.getColumnCount(); i++) m.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), i);
		return m;
	}

	private static int col(Map<String, Integer> cols, String name) {
		String n = name.trim().toLowerCase(Locale.ROOT);
		int dot = n.lastIndexOf('.');
		Integer i = cols.get(dot >= 0 ? n.substring(dot + 1) : n);
		if (i == null) throw new IllegalArgumentException("Column not found in the result: " + name);
		return i;
	}

	static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}
}
