package com.anishka.log_analyzer.model;

public class CriticalIncident {

	private String errorType;
	private Integer count;
	private String severity;

	public CriticalIncident(String errorType, Integer count, String severity) {

		this.errorType = errorType;
		this.count = count;
		this.severity = severity;
	}

	public String getErrorType() {
		return errorType;
	}

	public Integer getCount() {
		return count;
	}

	public String getSeverity() {
		return severity;
	}
}