package com.anishka.log_analyzer.parser;

/** Log formats the {@link UniversalLogParser} can recognise. */
public enum LogFormat {
	JSON("JSON (ELK / Logstash)"),
	SIEBEL("Siebel CRM log"),
	SPRING_BOOT("Spring Boot"),
	LOG4J("Log4j / Logback"),
	PYTHON("Python logging"),
	BRACKETED("Bracketed app log"),
	PIPE_DELIMITED("Pipe delimited"),
	LOGFMT("logfmt (key=value)"),
	ACCESS_LOG("Nginx / Apache access"),
	APACHE_ERROR("Apache error log"),
	SYSLOG_RFC5424("Syslog RFC 5424"),
	SYSLOG_RFC3164("Syslog RFC 3164"),
	GENERIC("Generic / unstructured");

	private final String label;

	LogFormat(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}
}
