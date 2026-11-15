package com.fitlife.repository;

import com.fitlife.entity.ChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    Page<ChatMessage> findBySessionIdOrderByIdAsc(Long sessionId, Pageable pageable);

    long countBySessionId(Long sessionId);
}
