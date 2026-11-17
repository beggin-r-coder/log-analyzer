package com.anishka.log_analyzer.source;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** How far each file of a folder source has been read, so restarts continue where they stopped. */
@Entity
@Table(name = "source_files", uniqueConstraints = @UniqueConstraint(columnNames = { "source_id", "path" }),
		indexes = @Index(name = "idx_source_files_source", columnList = "source_id"))
public class SourceFileState {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "source_id", nullable = false)
	private Long sourceId;

	@Column(nullable = false, length = 1000)
	private String path;

	/** Byte offset of the first line not yet read. */
	@Column(name = "read_offset", nullable = false)
	private long offset;

	@Column(name = "file_size", nullable = false)
	private long size;

	@Column(name = "last_modified")
	private LocalDateTime lastModified;

	/** First line of a Siebel log (component, task, UTC offset), re-applied when reading from the middle. */
	@Column(name = "header_line", length = 4000)
	private String headerLine;

	@Column(name = "events_read", nullable = false)
	private long eventsRead;

	public SourceFileState() {
	}

	public SourceFileState(Long sourceId, String path) {
		this.sourceId = sourceId;
		this.path = path;
	}

	public Long getId() { return id; }
	public Long getSourceId() { return sourceId; }
	public String getPath() { return path; }
	public long getOffset() { return offset; }
	public void setOffset(long offset) { this.offset = offset; }
	public long getSize() { return size; }
	public void setSize(long size) { this.size = size; }
	public LocalDateTime getLastModified() { return lastModified; }
	public void setLastModified(LocalDateTime lastModified) { this.lastModified = lastModified; }
	public String getHeaderLine() { return headerLine; }
	public void setHeaderLine(String headerLine) { this.headerLine = headerLine; }
	public long getEventsRead() { return eventsRead; }
	public void setEventsRead(long eventsRead) { this.eventsRead = eventsRead; }
}
