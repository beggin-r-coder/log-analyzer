package com.anishka.log_analyzer.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.anishka.log_analyzer.entity.LogRecord;

public interface LogRecordRepository extends JpaRepository<LogRecord, Long>, JpaSpecificationExecutor<LogRecord> {

	/** [level, count] */
	@Query("select r.level, count(r) from LogRecord r where r.timestamp >= :from and r.timestamp < :to group by r.level")
	List<Object[]> countByLevel(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

	/** [category, count, lastSeen] for ERROR/FATAL events. */
	@Query("""
			select r.errorCategory, count(r), max(r.timestamp) from LogRecord r
			where r.timestamp >= :from and r.timestamp < :to and r.levelRank >= 4
			group by r.errorCategory order by count(r) desc""")
	List<Object[]> errorCategories(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

	/** [category, service, count] for ERROR/FATAL events. */
	@Query("""
			select r.errorCategory, r.service, count(r) from LogRecord r
			where r.timestamp >= :from and r.timestamp < :to and r.levelRank >= 4
			group by r.errorCategory, r.service""")
	List<Object[]> errorCategoryServices(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

	/** [service, total, warnings, errors, lastSeen] */
	@Query("""
			select r.service, count(r),
			       sum(case when r.levelRank = 3 then 1 else 0 end),
			       sum(case when r.levelRank >= 4 then 1 else 0 end),
			       max(r.timestamp)
			from LogRecord r where r.timestamp >= :from and r.timestamp < :to
			group by r.service""")
	List<Object[]> serviceStats(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

	/**
	 * Time-bucketed counts computed inside the database:
	 * [bucketIndex, levelRank, count]. bucketIndex is the number of
	 * {@code bucket}-minute slots since 2000-01-01 00:00, after shifting by
	 * {@code shift} minutes so buckets align to the viewer's time zone.
	 */
	@Query(value = """
			select (datediff('MINUTE', timestamp '2000-01-01 00:00:00', log_time) + :shift) / :bucket as b,
			       level_rank, count(*)
			from log_records where log_time >= :from and log_time < :to
			group by b, level_rank""", nativeQuery = true)
	List<Object[]> bucketedCounts(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
			@Param("bucket") int bucketMinutes, @Param("shift") int shiftMinutes);

	Optional<LogRecord> findTopByOrderByTimestampAsc();

	/** [service, bucketIndex, events, errors] per {@code bucket}-minute slot, for SLA availability. */
	@Query(value = """
			select service, datediff('MINUTE', timestamp '2000-01-01 00:00:00', log_time) / :bucket as b,
			       count(*), sum(case when level_rank >= 4 then 1 else 0 end)
			from log_records where log_time >= :from and log_time < :to
			group by service, b""", nativeQuery = true)
	List<Object[]> serviceBucketCounts(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
			@Param("bucket") int bucketMinutes);

	/** Failed (ERROR/FATAL) events of the given services, for business-impact estimates. */
	@Query("select r from LogRecord r where r.timestamp >= :from and r.timestamp < :to and r.levelRank >= 4 and r.service in :services")
	List<LogRecord> failedEvents(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
			@Param("services") java.util.Collection<String> services);

	/** [service, message] of INFO events that mention an amount, to learn typical transaction values. */
	@Query("select r.service, r.message from LogRecord r where r.timestamp >= :from and r.levelRank = 2 and (r.message like '%INR %' or r.message like '%amount=%')")
	List<Object[]> amountSamples(@Param("from") LocalDateTime from, Pageable page);

	/** Newest records at or above a severity, for NLP clustering. */
	List<LogRecord> findByTimestampGreaterThanEqualAndLevelRankGreaterThanEqualOrderByTimestampDesc(LocalDateTime from,
			int minRank, Pageable page);

	/** Other events of the same request, for single-error context. */
	List<LogRecord> findTop25ByTraceIdAndTimestampBetweenOrderByTimestampAsc(String traceId, LocalDateTime from,
			LocalDateTime to);

	/** Warnings/errors of the same service around an event. */
	List<LogRecord> findTop300ByServiceAndLevelRankGreaterThanEqualAndTimestampBetweenOrderByTimestampAsc(String service,
			int minRank, LocalDateTime from, LocalDateTime to);

	/** [sourceFormat, count] */
	@Query("select r.sourceFormat, count(r) from LogRecord r where r.timestamp >= :from and r.timestamp < :to group by r.sourceFormat order by count(r) desc")
	List<Object[]> formatCounts(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

	@Query("select distinct r.service from LogRecord r where r.service is not null order by r.service")
	List<String> distinctServices();

	@Query("select distinct r.errorCategory from LogRecord r where r.errorCategory is not null order by r.errorCategory")
	List<String> distinctCategories();

	@Query("select max(r.timestamp) from LogRecord r")
	LocalDateTime latestTimestamp();

	@Transactional
	@Modifying
	@Query("delete from LogRecord r where r.timestamp < :cutoff")
	int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
