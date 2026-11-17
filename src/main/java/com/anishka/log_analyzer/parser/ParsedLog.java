package com.anishka.log_analyzer.parser;

import java.time.LocalDateTime;

/** Format-independent result of parsing one log event (possibly multi-line). */
public class ParsedLog {

	private LocalDateTime timestamp;
	private String level;
	private String service;
	private String host;
	private String thread;
	private String logger;
	private String traceId;
	private String clientIp;
	private String message;
	private LogFormat format;
	private String rawLine;

	public LocalDateTime getTimestamp() {
		return timestamp;
	}

	public ParsedLog timestamp(LocalDateTime timestamp) {
		this.timestamp = timestamp;
		return this;
	}

	public String getLevel() {
		return level;
	}

	public ParsedLog level(String level) {
		this.level = level;
		return this;
	}

	public String getService() {
		return service;
	}

	public ParsedLog service(String service) {
		this.service = blankToNull(service);
		return this;
	}

	public String getHost() {
		return host;
	}

	public ParsedLog host(String host) {
		this.host = blankToNull(host);
		return this;
	}

	public String getThread() {
		return thread;
	}

	public ParsedLog thread(String thread) {
		this.thread = blankToNull(thread);
		return this;
	}

	public String getLogger() {
		return logger;
	}

	public ParsedLog logger(String logger) {
		this.logger = blankToNull(logger);
		return this;
	}

	public String getTraceId() {
		return traceId;
	}

	public ParsedLog traceId(String traceId) {
		this.traceId = blankToNull(traceId);
		return this;
	}

	public String getClientIp() {
		return clientIp;
	}

	public ParsedLog clientIp(String clientIp) {
		this.clientIp = blankToNull(clientIp);
		return this;
	}

	public String getMessage() {
		return message;
	}

	public ParsedLog message(String message) {
		this.message = message == null ? "" : message.trim();
		return this;
	}

	public LogFormat getFormat() {
		return format;
	}

	public ParsedLog format(LogFormat format) {
		this.format = format;
		return this;
	}

	public String getRawLine() {
		return rawLine;
	}

	public ParsedLog rawLine(String rawLine) {
		this.rawLine = rawLine;
		return this;
	}

	/** Appends a continuation line (e.g. a stack-trace frame) to this event. */
	void appendContinuation(String line) {
		this.message = this.message + "\n" + line;
		this.rawLine = this.rawLine + "\n" + line;
	}

	private static String blankToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() || t.equals("-") ? null : t;
	}
}
