package com.anishka.log_analyzer.stream;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.anishka.log_analyzer.dto.LogRecordDto;

/**
 * Pushes newly stored logs to every connected browser using Server-Sent
 * Events, so the live feed updates the moment a row is committed to H2.
 */
@Component
public class LogStreamBroadcaster {

	/** Large back-fills (e.g. the initial seed) only push their newest rows. */
	private static final int MAX_PER_PUSH = 200;
	private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

	private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

	public SseEmitter subscribe() {
		SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
		emitter.onCompletion(() -> emitters.remove(emitter));
		emitter.onTimeout(() -> emitters.remove(emitter));
		emitter.onError(e -> emitters.remove(emitter));
		emitters.add(emitter);
		send(emitter, SseEmitter.event().name("hello").data(Map.of("clients", emitters.size()), MediaType.APPLICATION_JSON));
		return emitter;
	}

	public int clientCount() {
		return emitters.size();
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onLogsIngested(LogsIngestedEvent event) {
		if (emitters.isEmpty() || event.records().isEmpty()) {
			return;
		}
		List<LogRecordDto> records = event.records();
		if (records.size() > MAX_PER_PUSH) {
			records = records.subList(records.size() - MAX_PER_PUSH, records.size());
		}
		for (SseEmitter emitter : emitters) {
			send(emitter, SseEmitter.event().name("logs").data(records, MediaType.APPLICATION_JSON));
		}
	}

	/** Keeps idle connections open through proxies and detects dead clients. */
	@Scheduled(fixedDelay = 15_000)
	public void heartbeat() {
		for (SseEmitter emitter : emitters) {
			send(emitter, SseEmitter.event().comment("ping"));
		}
	}

	private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
		synchronized (emitter) { // emitters are not thread-safe; ingest + heartbeat run on different threads
			try {
				emitter.send(event);
			} catch (IOException | IllegalStateException e) {
				emitters.remove(emitter);
				emitter.completeWithError(e);
			}
		}
	}
}
