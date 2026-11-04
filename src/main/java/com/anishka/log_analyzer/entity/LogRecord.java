package com.anishka.log_analyzer.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * A single normalised log event, regardless of the format it arrived in.
 * The original line is kept in {@link #rawLine} for audit purposes, as is
 * standard practice for BFSI log retention.
 */
@Entity
@Table(name = "log_records", indexes = {
		@Index(name = "idx_log_time", columnList = "log_time"),
		@Index(name = "idx_log_level_time", columnList = "log_level, log_time"),
		@Index(name = "idx_log_service", columnList = "service"),
		@Index(name = "idx_log_category", columnList = "error_category"),
		@Index(name = "idx_log_trace", columnList = "trace_id") })
public class LogRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "log_seq")
	@SequenceGenerator(name = "log_seq", sequenceName = "log_records_seq", allocationSize = 100)
	private Long id;

	@Column(name = "log_time", nullable = false)
	private LocalDateTime timestamp;

	@Column(name = "log_level", nullable = false, length = 8)
	private String level;

	/** Numeric severity used for sorting: TRACE=0 ... FATAL=5. */
	@Column(name = "level_rank", nullable = false)
	private int levelRank;

	@Column(length = 64)
	private String service;

	@Column(length = 64)
	private String host;

	@Column(length = 64)
	private String thread;

	@Column(length = 128)
	private String logger;

	@Column(name = "trace_id", length = 64)
	private String traceId;

	@Column(name = "client_ip", length = 45)
	private String clientIp;

	@Column(length = 2000)
	private String message;

	@Column(name = "error_category", length = 48)
	private String errorCategory;

	@Column(name = "source_format", length = 24)
	private String sourceFormat;

	@Column(name = "raw_line", length = 4000)
	private String rawLine;

	@Column(name = "ingested_at", nullable = false)
	private LocalDateTime ingestedAt;

	public Long getId() {
		return id;
	}

	public LocalDateTime getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(LocalDateTime timestamp) {
		this.timestamp = timestamp;
	}

	public String getLevel() {
		return level;
	}

	public void setLevel(String level) {
		this.level = level;
	}

	public int getLevelRank() {
		return levelRank;
	}

	public void setLevelRank(int levelRank) {
		this.levelRank = levelRank;
	}

	public String getService() {
		return service;
	}

	public void setService(String service) {
		this.service = service;
	}

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public String getThread() {
		return thread;
	}

	public void setThread(String thread) {
		this.thread = thread;
	}

	public String getLogger() {
		return logger;
	}

	public void setLogger(String logger) {
		this.logger = logger;
	}

	public String getTraceId() {
		return traceId;
	}

	public void setTraceId(String traceId) {
		this.traceId = traceId;
	}

	public String getClientIp() {
		return clientIp;
	}

	public void setClientIp(String clientIp) {
		this.clientIp = clientIp;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public String getErrorCategory() {
		return errorCategory;
	}

	public void setErrorCategory(String errorCategory) {
		this.errorCategory = errorCategory;
	}

	public String getSourceFormat() {
		return sourceFormat;
	}

	public void setSourceFormat(String sourceFormat) {
		this.sourceFormat = sourceFormat;
	}

	public String getRawLine() {
		return rawLine;
	}

	public void setRawLine(String rawLine) {
		this.rawLine = rawLine;
	}

	public LocalDateTime getIngestedAt() {
		return ingestedAt;
	}

	public void setIngestedAt(LocalDateTime ingestedAt) {
		this.ingestedAt = ingestedAt;
	}
}
