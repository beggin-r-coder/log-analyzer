package com.anishka.log_analyzer.source;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SourceFileStateRepository extends JpaRepository<SourceFileState, Long> {

	List<SourceFileState> findBySourceId(Long sourceId);

	@Transactional
	@Modifying
	@Query("delete from SourceFileState s where s.sourceId = :id")
	void deleteBySource(@Param("id") Long id);
}
