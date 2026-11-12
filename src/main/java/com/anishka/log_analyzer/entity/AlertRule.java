package com.anishka.log_analyzer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Opens an incident when a service crosses a threshold over a sliding window,
 * e.g. "error rate >= 5% over 5 minutes, with at least 5 errors".
 */
@Entity
@Table(name = "alert_rules")
public class AlertRule {

	public static final String ERROR_RATE = "ERROR_RATE", ERROR_COUNT = "ERROR_COUNT";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 120)
	private String name;

	/** A service name, or "*" for every service. */
	@Column(nullable = false, length = 64)
	private String service;

	@Column(nullable = false, length = 16)
	private String metric;

	@Column(nullable = false)
	private double threshold;

	@Column(name = "window_minutes", nullable = false)
	private int windowMinutes;

	/** For ERROR_RATE: ignore windows with fewer errors than this (avoids alerts on tiny traffic). */
	@Column(name = "min_errors", nullable = false)
	private int minErrors;

	@Column(nullable = false, length = 16)
	private String severity;

	@Column(nullable = false)
	private boolean enabled = true;

	public AlertRule() {
	}

	public AlertRule(String name, String service, String metric, double threshold, int windowMinutes, int minErrors, String severity) {
		this.name = name;
		this.service = service;
		this.metric = metric;
		this.threshold = threshold;
		this.windowMinutes = windowMinutes;
		this.minErrors = minErrors;
		this.severity = severity;
	}

	public Long getId() { return id; }
	public String getName() { return name; }
	public void setName(String name) { this.name = name; }
	public String getService() { return service; }
	public void setService(String service) { this.service = service; }
	public String getMetric() { return metric; }
	public void setMetric(String metric) { this.metric = metric; }
	public double getThreshold() { return threshold; }
	public void setThreshold(double threshold) { this.threshold = threshold; }
	public int getWindowMinutes() { return windowMinutes; }
	public void setWindowMinutes(int windowMinutes) { this.windowMinutes = windowMinutes; }
	public int getMinErrors() { return minErrors; }
	public void setMinErrors(int minErrors) { this.minErrors = minErrors; }
	public String getSeverity() { return severity; }
	public void setSeverity(String severity) { this.severity = severity; }
	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }

	public boolean appliesTo(String svc) {
		return "*".equals(service) || service.equals(svc);
	}
}
