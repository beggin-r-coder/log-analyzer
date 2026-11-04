package com.anishka.log_analyzer.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.anishka.log_analyzer.dto.LogQuery;
import com.anishka.log_analyzer.dto.LogRecordDto;
import com.anishka.log_analyzer.dto.PageResponse;
import com.anishka.log_analyzer.entity.LogRecord;
import com.anishka.log_analyzer.repository.LogRecordRepository;

import jakarta.persistence.criteria.Predicate;

/** Search, filter, sort and page persisted logs for the explorer table. */
@Service
@Transactional(readOnly = true)
public class LogQueryService {

	/** UI column -> entity property. Anything else is rejected, so sorting is injection-safe. */
	private static final Map<String, String> SORTABLE = Map.of("timestamp", "timestamp", "level", "levelRank",
			"service", "service", "category", "errorCategory", "message", "message", "host", "host", "format",
			"sourceFormat", "traceId", "traceId", "id", "id");

	public static final int MAX_PAGE_SIZE = 200;
	public static final int MAX_EXPORT_ROWS = 10_000;

	private final LogRecordRepository repository;

	public LogQueryService(LogRecordRepository repository) {
		this.repository = repository;
	}

	public PageResponse<LogRecordDto> search(LogQuery q) {
		int size = Math.max(1, Math.min(q.size() == null ? 25 : q.size(), MAX_PAGE_SIZE));
		int page = Math.max(0, q.page() == null ? 0 : q.page());
		return PageResponse.of(repository.findAll(spec(q), PageRequest.of(page, size, sort(q))).map(LogRecordDto::from));
	}

	public List<LogRecordDto> export(LogQuery q) {
		return repository.findAll(spec(q), PageRequest.of(0, MAX_EXPORT_ROWS, sort(q))).map(LogRecordDto::from)
				.getContent();
	}

	public LogRecordDto byId(long id) {
		return repository.findById(id).map(LogRecordDto::from)
				.orElseThrow(() -> new IllegalArgumentException("No log with id " + id));
	}

	private static Sort sort(LogQuery q) {
		String prop = SORTABLE.getOrDefault(q.sort() == null ? "timestamp" : q.sort(), null);
		if (prop == null) {
			throw new IllegalArgumentException("Unsupported sort field: " + q.sort());
		}
		Sort.Direction dir = "asc".equalsIgnoreCase(q.dir()) ? Sort.Direction.ASC : Sort.Direction.DESC;
		return Sort.by(dir, prop).and(Sort.by(dir, "id"));
	}

	static Specification<LogRecord> spec(LogQuery q) {
		return (root, query, cb) -> {
			List<Predicate> preds = new ArrayList<>();

			TimeRange range = TimeRange.of(q.range(), TimeRange.H24);
			if (range != TimeRange.ALL) {
				preds.add(cb.greaterThanOrEqualTo(root.get("timestamp"), LocalDateTime.now().minus(range.duration())));
			}
			if (q.levels() != null) {
				List<String> levels = q.levels().stream().filter(l -> l != null && !l.isBlank())
						.map(l -> l.trim().toUpperCase(Locale.ROOT)).toList();
				if (!levels.isEmpty()) {
					preds.add(root.get("level").in(levels));
				}
			}
			if (q.service() != null && !q.service().isBlank()) {
				preds.add(cb.equal(root.get("service"), q.service().trim()));
			}
			if (q.category() != null && !q.category().isBlank()) {
				preds.add(cb.equal(root.get("errorCategory"), q.category().trim()));
			}
			// every search term must match at least one searchable field
			if (q.q() != null && !q.q().isBlank()) {
				for (String term : q.q().trim().toLowerCase(Locale.ROOT).split("\\s+")) {
					String like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
					preds.add(cb.or(
							cb.like(cb.lower(root.get("message")), like, '\\'),
							cb.like(cb.lower(root.get("service")), like, '\\'),
							cb.like(cb.lower(root.get("traceId")), like, '\\'),
							cb.like(cb.lower(root.get("host")), like, '\\'),
							cb.like(cb.lower(root.get("logger")), like, '\\'),
							cb.like(cb.lower(root.get("clientIp")), like, '\\'),
							cb.like(cb.lower(root.get("errorCategory")), like, '\\'),
							cb.like(cb.lower(root.get("level")), like, '\\')));
				}
			}
			return cb.and(preds.toArray(Predicate[]::new));
		};
	}
}
