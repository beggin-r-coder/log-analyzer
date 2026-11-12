package com.anishka.log_analyzer.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.anishka.log_analyzer.entity.AlertRule;

public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

	List<AlertRule> findByEnabledTrue();
}
