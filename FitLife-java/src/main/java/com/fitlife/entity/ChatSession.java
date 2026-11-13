package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** P3：AI 对话会话（服务端持久化，跨设备恢复）。session_key 兼容前端已有 UUID。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "chat_session",
       indexes = {
           @Index(name = "idx_cs_user", columnList = "user_id, last_active_at")
       })
public class ChatSession {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_key", length = 64, unique = true)
    private String sessionKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "plan_id")
    private Long planId;

    @Column(length = 120)
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_active_at", nullable = false)
    private LocalDateTime lastActiveAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (lastActiveAt == null) lastActiveAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        lastActiveAt = LocalDateTime.now();
    }
}
