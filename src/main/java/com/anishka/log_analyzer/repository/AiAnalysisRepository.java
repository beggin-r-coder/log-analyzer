package com.anishka.log_analyzer.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.anishka.log_analyzer.entity.AiAnalysisRecord;

public interface AiAnalysisRepository extends JpaRepository<AiAnalysisRecord, Long> {

	Optional<AiAnalysisRecord> findTopByScopeAndRefIdOrderByCreatedAtDesc(String scope, Long refId);

	Optional<AiAnalysisRecord> findTopByScopeAndRangeCodeOrderByCreatedAtDesc(String scope, String rangeCode);
}
