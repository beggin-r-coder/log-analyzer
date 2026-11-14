package com.anishka.log_analyzer.dto;

import java.util.List;

/**
 * Search/sort/paging parameters for the log explorer. Bound from query
 * parameters, e.g. {@code /api/logs?q=timeout&levels=ERROR,FATAL&sort=timestamp&dir=desc}.
 */
public record LogQuery(String q, List<String> levels, String service, String category, String range, String sort,
		String dir, Integer page, Integer size) {
}
