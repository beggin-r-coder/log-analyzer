package com.anishka.log_analyzer.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.anishka.log_analyzer.entity.IncidentEvent;

public interface IncidentEventRepository extends JpaRepository<IncidentEvent, Long> {

	List<IncidentEvent> findByIncidentIdOrderByAtAscIdAsc(Long incidentId);
}
