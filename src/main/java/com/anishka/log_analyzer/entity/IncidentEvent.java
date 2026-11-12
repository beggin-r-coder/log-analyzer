package com.anishka.log_analyzer.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** One entry on an incident's timeline. */
@Entity
@Table(name = "incident_events", indexes = @Index(name = "idx_inc_event", columnList = "incident_id, at"))
public class IncidentEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "incident_id", nullable = false)
	private Long incidentId;

	@Column(nullable = false)
	private LocalDateTime at;

	/** OPENED, ACKNOWLEDGED, NOTE, UPDATED, RESOLVED, RECOVERED */
	@Column(nullable = false, length = 16)
	private String type;

	@Column(length = 64)
	private String actor;

	@Column(length = 2000)
	private String text;

	public IncidentEvent() {
	}

	public IncidentEvent(Long incidentId, LocalDateTime at, String type, String actor, String text) {
		this.incidentId = incidentId;
		this.at = at;
		this.type = type;
		this.actor = actor;
		this.text = text;
	}

	public Long getId() { return id; }
	public Long getIncidentId() { return incidentId; }
	public LocalDateTime getAt() { return at; }
	public String getType() { return type; }
	public String getActor() { return actor; }
	public String getText() { return text; }
}
