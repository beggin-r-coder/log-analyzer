package com.anishka.log_analyzer.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** An operational incident: opened by an alert rule, then acknowledged and resolved by people (or recovery). */
@Entity
@Table(name = "incidents", indexes = { @Index(name = "idx_inc_status", columnList = "status"),
		@Index(name = "idx_inc_opened", columnList = "opened_at"), @Index(name = "idx_inc_service", columnList = "service") })
public class Incident {

	public static final String OPEN = "OPEN", ACKNOWLEDGED = "ACKNOWLEDGED", RESOLVED = "RESOLVED";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, length = 64)
	private String service;

	@Column(nullable = false, length = 16)
	private String severity;

	@Column(nullable = false, length = 16)
	private String status;

	@Column(name = "rule_id")
	private Long ruleId;

	@Column(name = "rule_name", length = 120)
	private String ruleName;

	@Column(length = 64)
	private String owner;

	@Column(name = "opened_at", nullable = false)
	private LocalDateTime openedAt;

	@Column(name = "acknowledged_at")
	private LocalDateTime acknowledgedAt;

	@Column(name = "acknowledged_by", length = 64)
	private String acknowledgedBy;

	@Column(name = "resolved_at")
	private LocalDateTime resolvedAt;

	@Column(name = "resolved_by", length = 64)
	private String resolvedBy;

	@Column(name = "last_breach_at")
	private LocalDateTime lastBreachAt;

	@Column(name = "peak_error_rate")
	private double peakErrorRate;

	public Long getId() { return id; }
	public String getTitle() { return title; }
	public void setTitle(String title) { this.title = title; }
	public String getService() { return service; }
	public void setService(String service) { this.service = service; }
	public String getSeverity() { return severity; }
	public void setSeverity(String severity) { this.severity = severity; }
	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }
	public Long getRuleId() { return ruleId; }
	public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
	public String getRuleName() { return ruleName; }
	public void setRuleName(String ruleName) { this.ruleName = ruleName; }
	public String getOwner() { return owner; }
	public void setOwner(String owner) { this.owner = owner; }
	public LocalDateTime getOpenedAt() { return openedAt; }
	public void setOpenedAt(LocalDateTime openedAt) { this.openedAt = openedAt; }
	public LocalDateTime getAcknowledgedAt() { return acknowledgedAt; }
	public void setAcknowledgedAt(LocalDateTime acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
	public String getAcknowledgedBy() { return acknowledgedBy; }
	public void setAcknowledgedBy(String acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }
	public LocalDateTime getResolvedAt() { return resolvedAt; }
	public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
	public String getResolvedBy() { return resolvedBy; }
	public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
	public LocalDateTime getLastBreachAt() { return lastBreachAt; }
	public void setLastBreachAt(LocalDateTime lastBreachAt) { this.lastBreachAt = lastBreachAt; }
	public double getPeakErrorRate() { return peakErrorRate; }
	public void setPeakErrorRate(double peakErrorRate) { this.peakErrorRate = peakErrorRate; }

	public boolean isActive() {
		return !RESOLVED.equals(status);
	}
}
