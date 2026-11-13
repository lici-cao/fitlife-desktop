package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** P3：对话消息明细。action 记录 GENERATE/REVISE/QUERY/CHAT/STREAM；refs 记 planId/version 等。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "chat_message",
       indexes = {
           @Index(name = "idx_cm_session", columnList = "session_id, id")
       })
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** user / assistant / system */
    @Column(nullable = false, length = 12)
    private String role;

    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Column(length = 24)
    private String action;

    @Column
    private Integer tokens;

    @Column(length = 512)
    private String refs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
