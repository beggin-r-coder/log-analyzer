package com.anishka.log_analyzer.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** Audit trail of every AI analysis: what was analysed, by which model, and the full result. */
@Entity
@Table(name = "ai_analyses", indexes = { @Index(name = "idx_ai_scope_ref", columnList = "scope, ref_id, created_at"),
		@Index(name = "idx_ai_scope_range", columnList = "scope, range_code, created_at") })
public class AiAnalysisRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** LOG (one log record) or OVERVIEW (all errors in a time range). */
	@Column(nullable = false, length = 16)
	private String scope;

	@Column(name = "ref_id")
	private Long refId;

	@Column(name = "range_code", length = 8)
	private String rangeCode;

	/** GEMINI or NLP (offline playbook). */
	@Column(nullable = false, length = 16)
	private String source;

	@Column(length = 64)
	private String model;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Lob
	@Column(nullable = false)
	private String payload;

	public Long getId() { return id; }
	public String getScope() { return scope; }
	public void setScope(String scope) { this.scope = scope; }
	public Long getRefId() { return refId; }
	public void setRefId(Long refId) { this.refId = refId; }
	public String getRangeCode() { return rangeCode; }
	public void setRangeCode(String rangeCode) { this.rangeCode = rangeCode; }
	public String getSource() { return source; }
	public void setSource(String source) { this.source = source; }
	public String getModel() { return model; }
	public void setModel(String model) { this.model = model; }
	public LocalDateTime getCreatedAt() { return createdAt; }
	public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
	public String getPayload() { return payload; }
	public void setPayload(String payload) { this.payload = payload; }
}
