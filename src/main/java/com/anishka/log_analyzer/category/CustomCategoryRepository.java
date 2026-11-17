package com.anishka.log_analyzer.category;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomCategoryRepository extends JpaRepository<CustomCategory, Long> {

	List<CustomCategory> findAllByOrderByIdAsc();
}
