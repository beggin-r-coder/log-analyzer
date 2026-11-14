package com.anishka.log_analyzer.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.anishka.log_analyzer.entity.Incident;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

	List<Incident> findByStatusInOrderByOpenedAtDesc(Collection<String> statuses);

	List<Incident> findByOpenedAtGreaterThanEqualOrderByOpenedAtDesc(LocalDateTime from);

	Optional<Incident> findFirstByServiceAndStatusIn(String service, Collection<String> statuses);
}
