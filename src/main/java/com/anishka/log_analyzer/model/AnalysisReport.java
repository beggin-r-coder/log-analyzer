package com.anishka.log_analyzer.model;

import java.util.List;
import java.util.Map;

public class AnalysisReport {

	private int totalLogs;
	private int infoLogs;
	private int warnLogs;
	private int errorLogs;
	private Map<String, Integer> errorMap;
	private List<CriticalIncident> criticalIncidents;
	private Map<String, Integer> hourlyErrors;

	public AnalysisReport(int totalLogs, int infoLogs, int warnLogs, int errorLogs, Map<String, Integer> errorMap) {

		this.totalLogs = totalLogs;
		this.infoLogs = infoLogs;
		this.warnLogs = warnLogs;
		this.errorLogs = errorLogs;
		this.errorMap = errorMap;
	}

	private double healthScore;

	public double getHealthScore() {
		return healthScore;
	}

	public void setHealthScore(double healthScore) {
		this.healthScore = healthScore;
	}

	public int getTotalLogs() {
		return totalLogs;
	}

	public void setTotalLogs(int totalLogs) {
		this.totalLogs = totalLogs;
	}

	public int getInfoLogs() {
		return infoLogs;
	}

	public void setInfoLogs(int infoLogs) {
		this.infoLogs = infoLogs;
	}

	public int getWarnLogs() {
		return warnLogs;
	}

	public void setWarnLogs(int warnLogs) {
		this.warnLogs = warnLogs;
	}

	public int getErrorLogs() {
		return errorLogs;
	}

	public void setErrorLogs(int errorLogs) {
		this.errorLogs = errorLogs;
	}

	public Map<String, Integer> getErrorMap() {
		return errorMap;
	}

	public void setErrorMap(Map<String, Integer> errorMap) {
		this.errorMap = errorMap;
	}

	public List<CriticalIncident> getCriticalIncidents() {
		return criticalIncidents;
	}

	public void setCriticalIncidents(List<CriticalIncident> criticalIncidents) {

		this.criticalIncidents = criticalIncidents;
	}

	public Map<String, Integer> getHourlyErrors() {
		return hourlyErrors;
	}

	public void setHourlyErrors(Map<String, Integer> hourlyErrors) {

		this.hourlyErrors = hourlyErrors;
	}
}