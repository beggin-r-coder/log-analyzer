package com.anishka.log_analyzer.dto;

import java.util.List;
import java.util.Map;

public record IngestResult(int events, boolean stored, Map<String, Long> formats, List<LogRecordDto> preview) {
}
