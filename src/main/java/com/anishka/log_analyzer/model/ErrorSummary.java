package com.anishka.log_analyzer.model;

import java.util.List;

public class ErrorSummary {

	private String errorType;
	private int count;
	private List<String> logs;

	public ErrorSummary(String errorType, int count, List<String> logs) {
		this.errorType = errorType;
		this.count = count;
		this.logs = logs;
	}

	public String getErrorType() {
		return errorType;
	}

	public int getCount() {
		return count;
	}

	public List<String> getLogs() {
		return logs;
	}
}