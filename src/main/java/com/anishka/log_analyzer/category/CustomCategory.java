package com.anishka.log_analyzer.category;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A category the user defines: log events matching its rules (e.g. message contains "Record not found") are counted
 * and shown as a KPI and / or a chart over time, and can raise an incident when they spike.
 */
@Entity
@Table(name = "custom_categories")
public class CustomCategory {

	public static final String LINE = "LINE", BAR = "BAR", AREA = "AREA", NONE = "NONE";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 80)
	private String name;

	@Column(length = 400)
	private String description;

	@Column(length = 16)
	private String color;

	@Column(nullable = false)
	private boolean enabled = true;

	/** True: every rule must match; false: any rule. */
	@Column(name = "match_all", nullable = false)
	private boolean matchAll;

	/** Only these levels (comma separated); empty means every level. */
	@Column(length = 64)
	private String levels;

	/** The rules as JSON: [{field, op, value, caseSensitive}]. */
	@Column(name = "rules_json", nullable = false, length = 8000)
	private String rulesJson = "[]";

	@Column(name = "show_kpi", nullable = false)
	private boolean showKpi = true;

	@Column(name = "chart_type", length = 8)
	private String chartType = LINE;

	@Column(name = "alert_enabled", nullable = false)
	private boolean alertEnabled;

	/** Alert when at least this many events match within {@link #alertWindowMinutes}. */
	@Column(name = "alert_threshold", nullable = false)
	private int alertThreshold = 10;

	@Column(name = "alert_window_minutes", nullable = false)
	private int alertWindowMinutes = 15;

	@Column(name = "alert_severity", length = 16)
	private String alertSeverity = "MEDIUM";

	@Column(name = "created_at")
	private LocalDateTime createdAt = LocalDateTime.now();

	public Long getId() { return id; }
	public String getName() { return name; }
	public void setName(String name) { this.name = name; }
	public String getDescription() { return description; }
	public void setDescription(String description) { this.description = description; }
	public String getColor() { return color; }
	public void setColor(String color) { this.color = color; }
	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public boolean isMatchAll() { return matchAll; }
	public void setMatchAll(boolean matchAll) { this.matchAll = matchAll; }
	public String getLevels() { return levels; }
	public void setLevels(String levels) { this.levels = levels; }
	public String getRulesJson() { return rulesJson; }
	public void setRulesJson(String rulesJson) { this.rulesJson = rulesJson; }
	public boolean isShowKpi() { return showKpi; }
	public void setShowKpi(boolean showKpi) { this.showKpi = showKpi; }
	public String getChartType() { return chartType; }
	public void setChartType(String chartType) { this.chartType = chartType; }
	public boolean isAlertEnabled() { return alertEnabled; }
	public void setAlertEnabled(boolean alertEnabled) { this.alertEnabled = alertEnabled; }
	public int getAlertThreshold() { return alertThreshold; }
	public void setAlertThreshold(int alertThreshold) { this.alertThreshold = alertThreshold; }
	public int getAlertWindowMinutes() { return alertWindowMinutes; }
	public void setAlertWindowMinutes(int alertWindowMinutes) { this.alertWindowMinutes = alertWindowMinutes; }
	public String getAlertSeverity() { return alertSeverity; }
	public void setAlertSeverity(String alertSeverity) { this.alertSeverity = alertSeverity; }
	public LocalDateTime getCreatedAt() { return createdAt; }
}
