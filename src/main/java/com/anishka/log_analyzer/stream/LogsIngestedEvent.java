package com.anishka.log_analyzer.stream;

import java.util.List;

import com.anishka.log_analyzer.dto.LogRecordDto;

/** Published inside the ingest transaction; delivered to browsers only after it commits. */
public record LogsIngestedEvent(List<LogRecordDto> records) {
}
