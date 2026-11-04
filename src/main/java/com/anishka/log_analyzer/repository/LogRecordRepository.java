package com.anishka.log_analyzer.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
