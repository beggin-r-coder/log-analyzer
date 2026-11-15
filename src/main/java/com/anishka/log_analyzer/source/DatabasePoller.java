package com.anishka.log_analyzer.source;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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
	private static final java.time.format.DateTimeFormatter SHOW_TS = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*(\\.[A-Za-z_][A-Za-z0-9_$#]*){0,2}");

	private final LogSourceRepository sources;
	private static final Pattern SYSTEM_SCHEMA = Pattern.compile("(?i)information_schema|pg_catalog|pg_toast|sys|system|mysql|performance_schema|"
			+ "ctxsys|mdsys|xdb|ordsys|ordplugins|ordata|olapsys|outln|dbsnmp|appqossys|wmsys|exfsys|lbacsys|dvsys|gsmadmin_internal|"
			+ "ojvmsys|audsys|apex_.*|flows_.*|si_informtn_schema|anonymous|xs\\$null|dbsfwuser|remote_scheduler_agent|gsmcatuser|"
			+ "sysman|mgmt_view|db_owner|db_.*|guest");

	private final LogIngestionService ingestion;
	private final SecretBox secrets;
	private final ColumnDetector detector;
	private final RowMapper rowMapper;

	public DatabasePoller(LogSourceRepository sources, LogIngestionService ingestion, SecretBox secrets, ColumnDetector detector,
			RowMapper rowMapper) {
		this.sources = sources;
		this.ingestion = ingestion;
		this.secrets = secrets;
		this.detector = detector;
		this.rowMapper = rowMapper;
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

	/** Connects and returns the first rows of the table / query, with each column's role worked out from the data. */
	public Map<String, Object> preview(LogSource src, String plainPassword) throws SQLException {
		try (Connection c = connect(src, plainPassword)) {
			Sample sample = sample(c, from(src), 200, 30);
			ColumnDetector.Detection d = detector.detect(sample.names(), sample.rows());
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("product", c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion());
			out.put("columns", sample.names());
			out.put("rows", display(sample.rows(), 10));
			out.put("suggested", d.mapping());
			out.put("detected", detection(d));
			return out;
		}
	}

	/**
	 * Looks through the database for tables and views that hold logs: likely names are checked first, a sample
	 * of rows is read from each and scored, and the best candidates come back with their column roles worked out.
	 */
	public Map<String, Object> discover(LogSource src, String plainPassword) throws SQLException {
		long deadline = System.currentTimeMillis() + 40_000;
		try (Connection c = connect(src, plainPassword)) {
			DatabaseMetaData md = c.getMetaData();
			String currentSchema = null;
			try {
				currentSchema = c.getSchema();
			} catch (SQLException | AbstractMethodError e) {
				// older drivers
			}
			List<String[]> tables = new ArrayList<>();
			try (ResultSet rs = md.getTables(c.getCatalog(), null, "%", new String[] { "TABLE", "VIEW" })) {
				while (rs.next() && tables.size() < 20000) {
					String schema = rs.getString("TABLE_SCHEM"), name = rs.getString("TABLE_NAME");
					if (name == null || SYSTEM_SCHEMA.matcher(schema == null ? "" : schema).matches()) continue;
					String q = schema == null || schema.isBlank() || schema.equalsIgnoreCase(currentSchema) ? name : schema + "." + name;
					if (IDENT.matcher(q).matches()) tables.add(new String[] { q, name, rs.getString("TABLE_TYPE") });
				}
			}
			// likely log tables first; in a small database every table is looked at
			tables.sort(java.util.Comparator.comparingInt((String[] t) -> -nameScore(t[1])));
			List<String[]> candidates = tables.size() <= 40 ? tables
					: tables.stream().filter(t -> nameScore(t[1]) > 0).limit(25).toList();
			List<Map<String, Object>> found = new ArrayList<>();
			for (String[] t : candidates) {
				if (System.currentTimeMillis() > deadline || found.size() >= 40) break;
				try {
					Sample sample = sample(c, t[0], 100, 10);
					if (sample.rows().isEmpty()) continue;
					ColumnDetector.Detection d = detector.detect(sample.names(), sample.rows());
					int score = Math.min(100, d.logLikeness() + Math.min(10, nameScore(t[1]) * 3));
					if (score < 40) continue;
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("table", t[0]);
					m.put("type", "VIEW".equalsIgnoreCase(t[2]) ? "view" : "table");
					m.put("score", score);
					m.put("columns", sample.names());
					m.put("sampleRows", sample.rows().size());
					m.put("suggested", d.mapping());
					m.put("detected", detection(d));
					found.add(m);
				} catch (SQLException | RuntimeException e) {
					log.debug("Skipped {} while looking for log tables: {}", t[0], e.toString());
				}
			}
			found.sort(java.util.Comparator.comparingInt((Map<String, Object> m) -> -(int) m.get("score")));
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("product", md.getDatabaseProductName() + " " + md.getDatabaseProductVersion());
			out.put("tablesInDatabase", tables.size());
			out.put("tablesChecked", candidates.size());
			out.put("tables", found.size() > 12 ? found.subList(0, 12) : found);
			return out;
		}
	}

	/** Higher for names that suggest logs (Siebel: S_SRM_REQUEST, S_AUDIT_ITEM, EIM / workflow logs). */
	static int nameScore(String table) {
		String t = table.toLowerCase(Locale.ROOT);
		int s = 0;
		if (t.matches(".*(log|logs|logging)(_.*|$)|.*_log.*|^log.*")) s += 3;
		if (t.matches(".*(audit|event|error|trace|alert|incident|exception|fault).*")) s += 2;
		if (t.matches(".*(history|hist|msg|message|journal|activity|monitor|s_srm_|s_audit_item|eim_|wf_).*")) s += 1;
		return s;
	}

	record Sample(List<String> names, List<List<Object>> rows) {
	}

	private static Sample sample(Connection c, String from, int maxRows, int timeoutSeconds) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + from)) {
			ps.setMaxRows(maxRows);
			ps.setQueryTimeout(timeoutSeconds);
			try (ResultSet rs = ps.executeQuery()) {
				ResultSetMetaData md = rs.getMetaData();
				List<String> names = new ArrayList<>();
				for (int i = 1; i <= md.getColumnCount(); i++) names.add(md.getColumnLabel(i));
				List<List<Object>> rows = new ArrayList<>();
				while (rs.next() && rows.size() < maxRows) {
					List<Object> r = new ArrayList<>();
					for (int i = 1; i <= names.size(); i++) r.add(value(rs.getObject(i)));
					rows.add(r);
				}
				return new Sample(names, rows);
			}
		}
	}

	/** JDBC values the detector understands: large text and odd driver types become strings. */
	private static Object value(Object o) throws SQLException {
		if (o instanceof java.sql.Clob clob) {
			long len = Math.min(clob.length(), 20000);
			return clob.getSubString(1, (int) len);
		}
		if (o instanceof Number || o instanceof java.util.Date || o instanceof java.time.temporal.Temporal || o == null) return o;
		if (o instanceof byte[]) return null;
		String s = o.toString();
		// Oracle TIMESTAMP and similar driver types print as dates
		return s;
	}

	static List<List<String>> display(List<List<Object>> rows, int max) {
		List<List<String>> out = new ArrayList<>();
		for (List<Object> r : rows.subList(0, Math.min(max, rows.size()))) {
			List<String> line = new ArrayList<>();
			for (Object o : r) {
				String v = o == null ? "" : o instanceof LocalDateTime t ? t.format(SHOW_TS) : o instanceof java.sql.Timestamp t ? t.toLocalDateTime().format(SHOW_TS)
						: o instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf(d.longValue()) : String.valueOf(o);
				line.add(v.length() > 160 ? v.substring(0, 159) + "…" : v);
			}
			out.add(line);
		}
		return out;
	}

	static Map<String, Object> detection(ColumnDetector.Detection d) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("confidence", d.confidence());
		m.put("reasons", d.reasons());
		m.put("logLikeness", d.logLikeness());
		return m;
	}

	/** Fills in any unmapped columns from a sample of the data (used when a source is saved without a mapping). */
	public void autoMap(LogSource src, String plainPassword) throws SQLException {
		try (Connection c = connect(src, plainPassword)) {
			Sample sample = sample(c, from(src), 200, 30);
			applyMapping(src, detector.detect(sample.names(), sample.rows()).mapping());
		}
	}

	static void applyMapping(LogSource s, Map<String, String> m) {
		if (!notBlank(s.getTsColumn())) s.setTsColumn(m.get("tsColumn"));
		if (!notBlank(s.getMessageColumn()) && !notBlank(s.getRawColumn())) {
			s.setMessageColumn(m.get("messageColumn"));
			s.setRawColumn(m.get("rawColumn"));
		}
		if (!notBlank(s.getLevelColumn())) s.setLevelColumn(m.get("levelColumn"));
		if (!notBlank(s.getServiceColumn())) s.setServiceColumn(m.get("serviceColumn"));
		if (!notBlank(s.getTraceColumn())) s.setTraceColumn(m.get("traceColumn"));
		if (!notBlank(s.getHostColumn())) s.setHostColumn(m.get("hostColumn"));
		if (!notBlank(s.getIdColumn())) s.setIdColumn(m.get("idColumn"));
	}

	// ------------------------------------------------------------------ mapping

	private List<ParsedLog> map(LogSource src, ResultSet rs, Map<String, Integer> cols) throws SQLException {
		List<Object> row = new ArrayList<>();
		for (int i = 1; i <= cols.size(); i++) row.add(value(rs.getObject(i)));
		return rowMapper.map(src, name -> row.get(col(cols, name) - 1), LogFormat.DATABASE);
	}

	static LocalDateTime timestamp(Object o) {
		return Timestamps.parse(o);
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
