package com.anishka.log_analyzer.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.anishka.log_analyzer.entity.LogRecord;
import com.anishka.log_analyzer.parser.ParsedLog;
import com.anishka.log_analyzer.parser.UniversalLogParser;
import com.anishka.log_analyzer.repository.LogRecordRepository;

/** Parses raw log text in any supported format and persists it. */
@Service
public class LogIngestionService {

	private final UniversalLogParser parser;
	private final ErrorClassifier classifier;
	private final LogRecordRepository repository;

	public LogIngestionService(UniversalLogParser parser, ErrorClassifier classifier,
			LogRecordRepository repository) {
		this.parser = parser;
		this.classifier = classifier;
		this.repository = repository;
	}

	/** Parses without saving – used by the parser playground. */
	public List<ParsedLog> preview(String text, String defaultService) {
		return parser.parse(text, defaultService);
	}

	@Transactional
	public List<LogRecord> ingest(String text, String defaultService) {
		return save(parser.parse(text, defaultService));
	}

	@Transactional
	public List<LogRecord> save(List<ParsedLog> parsed) {
		LocalDateTime now = LocalDateTime.now();
		List<LogRecord> records = new ArrayList<>(parsed.size());
		for (ParsedLog p : parsed) {
			records.add(toRecord(p, now));
		}
		return repository.saveAll(records);
	}

	/** Builds an unsaved record so the parser playground can show the classification too. */
	public LogRecord toPreviewRecord(ParsedLog p, LocalDateTime now) {
		return toRecord(p, now);
	}

	LogRecord toRecord(ParsedLog p, LocalDateTime ingestedAt) {
		LogRecord r = new LogRecord();
		r.setTimestamp(p.getTimestamp());
		r.setLevel(p.getLevel());
		r.setLevelRank(UniversalLogParser.levelRank(p.getLevel()));
		r.setService(cut(p.getService(), 64));
		r.setHost(cut(p.getHost(), 64));
		r.setThread(cut(p.getThread(), 64));
		r.setLogger(cut(p.getLogger(), 128));
		r.setTraceId(cut(p.getTraceId(), 64));
		r.setClientIp(cut(p.getClientIp(), 45));
		r.setMessage(cut(p.getMessage(), 2000));
		r.setSourceFormat(p.getFormat().name());
		r.setRawLine(cut(p.getRawLine(), 4000));
		r.setIngestedAt(ingestedAt);
		if (r.getLevelRank() >= 3) {
			r.setErrorCategory(classifier.classify(p.getMessage()));
		}
		return r;
	}

	private static String cut(String s, int max) {
		return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}
}
