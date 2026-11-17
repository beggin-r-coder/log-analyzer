package com.anishka.log_analyzer.source;

import java.util.List;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface LogSourceRepository extends JpaRepository<LogSource, Long> {

	/**
	 * Readers only touch their status columns, so they never overwrite what a person changed meanwhile
	 * (pausing, renaming...) with a stale copy of the source.
	 */
	@Transactional
	@Modifying
	@Query("""
			update LogSource s set s.lastRunAt = :runAt, s.lastSuccessAt = coalesce(:okAt, s.lastSuccessAt), s.lastError = :error,
			       s.eventsIngested = s.eventsIngested + :events, s.filesTracked = coalesce(:files, s.filesTracked)
			where s.id = :id""")
	int recordRun(@Param("id") Long id, @Param("runAt") LocalDateTime runAt, @Param("okAt") LocalDateTime okAt,
			@Param("error") String error, @Param("events") long events, @Param("files") Integer files);

	@Transactional
	@Modifying
	@Query("update LogSource s set s.watermarkTs = :ts, s.watermarkId = :wid where s.id = :id")
	int recordWatermark(@Param("id") Long id, @Param("ts") LocalDateTime ts, @Param("wid") String wid);

	List<LogSource> findByTypeAndEnabledTrue(String type);

	List<LogSource> findByDemoTrueAndEnabledTrue();
}
