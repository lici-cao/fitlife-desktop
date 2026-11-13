package com.fitlife.repository;

import com.fitlife.entity.KnowledgeItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeItemRepository extends JpaRepository<KnowledgeItem, Long> {

    List<KnowledgeItem> findByEnabled(Integer enabled);

    Optional<KnowledgeItem> findByCode(String code);
}
