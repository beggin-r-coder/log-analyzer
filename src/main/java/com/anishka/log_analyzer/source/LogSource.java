package com.anishka.log_analyzer.source;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Where logs come from: a folder of log files (e.g. a Siebel server's log directory or a network share)
 * or a database table / query. Sources are read-only: the app never writes to them.
 */
@Entity
@Table(name = "log_sources")
public class LogSource {

	public static final String FOLDER = "FOLDER", DATABASE = "DATABASE";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(nullable = false, length = 16)
	private String type;

	@Column(nullable = false)
	private boolean enabled = true;

	/** Managed sample: the app writes realistic Siebel logs into this folder itself. */
	@Column(nullable = false)
	private boolean demo;

	@Column(name = "created_at")
	private LocalDateTime createdAt = LocalDateTime.now();

	// ---------- folder ----------
	@Column(length = 1000)
	private String path;

	@Column(name = "file_pattern", length = 200)
	private String filePattern = "*.log";

	@Column(nullable = false)
	private boolean recursive;

	/** On first scan, files last changed before this many days ago are skipped (only new lines are read). */
	@Column(name = "backfill_days", nullable = false)
	private int backfillDays = 1;

	@Column(name = "default_service", length = 64)
	private String defaultService;

	// ---------- database ----------
	@Column(name = "db_type", length = 16)
	private String dbType;

	@Column(length = 255)
	private String host;

	private Integer port;

	@Column(name = "database_name", length = 255)
	private String databaseName;

	@Column(length = 128)
	private String username;

	@Column(name = "password_enc", length = 1000)
	private String passwordEnc;

	@Column(name = "jdbc_url", length = 1000)
	private String jdbcUrl;

	/** A table/view name, or a full SELECT statement. */
	@Column(name = "query_text", length = 4000)
	private String queryText;

	@Column(name = "ts_column", length = 128)
	private String tsColumn;
	@Column(name = "level_column", length = 128)
	private String levelColumn;
	@Column(name = "service_column", length = 128)
	private String serviceColumn;
	@Column(name = "message_column", length = 128)
	private String messageColumn;
	@Column(name = "trace_column", length = 128)
	private String traceColumn;
	@Column(name = "host_column", length = 128)
	private String hostColumn;
	/** A column holding a complete raw log line, parsed like a file line (overrides level/message mapping). */
	@Column(name = "raw_column", length = 128)
	private String rawColumn;
	/** Increasing key used to fetch only new rows; falls back to the timestamp column. */
	@Column(name = "id_column", length = 128)
	private String idColumn;

	@Column(name = "poll_seconds", nullable = false)
	private int pollSeconds = 15;

	// ---------- status ----------
	@Column(name = "last_run_at")
	private LocalDateTime lastRunAt;
	@Column(name = "last_success_at")
	private LocalDateTime lastSuccessAt;
	@Column(name = "last_error", length = 1000)
	private String lastError;
	@Column(name = "events_ingested", nullable = false)
	private long eventsIngested;
	@Column(name = "files_tracked", nullable = false)
	private int filesTracked;
	@Column(name = "watermark_ts")
	private LocalDateTime watermarkTs;
	@Column(name = "watermark_id", length = 128)
	private String watermarkId;

	public Long getId() { return id; }
	public String getName() { return name; }
	public void setName(String name) { this.name = name; }
	public String getType() { return type; }
	public void setType(String type) { this.type = type; }
	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public boolean isDemo() { return demo; }
	public void setDemo(boolean demo) { this.demo = demo; }
	public LocalDateTime getCreatedAt() { return createdAt; }
	public String getPath() { return path; }
	public void setPath(String path) { this.path = path; }
	public String getFilePattern() { return filePattern; }
	public void setFilePattern(String filePattern) { this.filePattern = filePattern; }
	public boolean isRecursive() { return recursive; }
	public void setRecursive(boolean recursive) { this.recursive = recursive; }
	public int getBackfillDays() { return backfillDays; }
	public void setBackfillDays(int backfillDays) { this.backfillDays = backfillDays; }
	public String getDefaultService() { return defaultService; }
	public void setDefaultService(String defaultService) { this.defaultService = defaultService; }
	public String getDbType() { return dbType; }
	public void setDbType(String dbType) { this.dbType = dbType; }
	public String getHost() { return host; }
	public void setHost(String host) { this.host = host; }
	public Integer getPort() { return port; }
	public void setPort(Integer port) { this.port = port; }
	public String getDatabaseName() { return databaseName; }
	public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
	public String getUsername() { return username; }
	public void setUsername(String username) { this.username = username; }
	public String getPasswordEnc() { return passwordEnc; }
	public void setPasswordEnc(String passwordEnc) { this.passwordEnc = passwordEnc; }
	public String getJdbcUrl() { return jdbcUrl; }
	public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
	public String getQueryText() { return queryText; }
	public void setQueryText(String queryText) { this.queryText = queryText; }
	public String getTsColumn() { return tsColumn; }
	public void setTsColumn(String tsColumn) { this.tsColumn = tsColumn; }
	public String getLevelColumn() { return levelColumn; }
	public void setLevelColumn(String levelColumn) { this.levelColumn = levelColumn; }
	public String getServiceColumn() { return serviceColumn; }
	public void setServiceColumn(String serviceColumn) { this.serviceColumn = serviceColumn; }
	public String getMessageColumn() { return messageColumn; }
	public void setMessageColumn(String messageColumn) { this.messageColumn = messageColumn; }
	public String getTraceColumn() { return traceColumn; }
	public void setTraceColumn(String traceColumn) { this.traceColumn = traceColumn; }
	public String getHostColumn() { return hostColumn; }
	public void setHostColumn(String hostColumn) { this.hostColumn = hostColumn; }
	public String getRawColumn() { return rawColumn; }
	public void setRawColumn(String rawColumn) { this.rawColumn = rawColumn; }
	public String getIdColumn() { return idColumn; }
	public void setIdColumn(String idColumn) { this.idColumn = idColumn; }
	public int getPollSeconds() { return pollSeconds; }
	public void setPollSeconds(int pollSeconds) { this.pollSeconds = pollSeconds; }
	public LocalDateTime getLastRunAt() { return lastRunAt; }
	public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }
	public LocalDateTime getLastSuccessAt() { return lastSuccessAt; }
	public void setLastSuccessAt(LocalDateTime lastSuccessAt) { this.lastSuccessAt = lastSuccessAt; }
	public String getLastError() { return lastError; }
	public void setLastError(String lastError) { this.lastError = lastError == null ? null : lastError.length() > 1000 ? lastError.substring(0, 999) : lastError; }
	public long getEventsIngested() { return eventsIngested; }
	public void setEventsIngested(long eventsIngested) { this.eventsIngested = eventsIngested; }
	public int getFilesTracked() { return filesTracked; }
	public void setFilesTracked(int filesTracked) { this.filesTracked = filesTracked; }
	public LocalDateTime getWatermarkTs() { return watermarkTs; }
	public void setWatermarkTs(LocalDateTime watermarkTs) { this.watermarkTs = watermarkTs; }
	public String getWatermarkId() { return watermarkId; }
	public void setWatermarkId(String watermarkId) { this.watermarkId = watermarkId; }
}
