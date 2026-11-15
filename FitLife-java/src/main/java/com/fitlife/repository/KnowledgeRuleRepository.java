package com.fitlife.repository;

import com.fitlife.entity.KnowledgeRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeRuleRepository extends JpaRepository<KnowledgeRule, Long> {

    List<KnowledgeRule> findByEnabled(Integer enabled);

    Optional<KnowledgeRule> findByCode(String code);
}
