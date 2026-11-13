package com.fitlife.repository;

import com.fitlife.entity.PromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, Long> {

    Optional<PromptTemplate> findByCodeAndActive(String code, Integer active);

    List<PromptTemplate> findByCodeOrderByVersionDesc(String code);
}
