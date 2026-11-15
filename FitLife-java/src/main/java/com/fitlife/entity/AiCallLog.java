package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 大模型调用计量（P2 可靠性改造的落库证据）。
 *
 * <p>每次 guarded 调用（含失败与跨厂商转移）写一行，
 * 支撑「生成成功率 / P95 延迟 / 降级率 / token 成本」四组答辩指标。
 */
@Entity
@Table(name = "ai_call_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PLAN_GEN / CHAT / MATCH / DIET ... */
    @Column(nullable = false, length = 32)
    private String endpoint;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "model_id", length = 64)
    private String modelId;

    @Column(length = 32)
    private String provider;

    /** 同厂商内重试次数 */
    @Column(nullable = false)
    @Builder.Default
    private Integer attempts = 1;

    /** 跨厂商转移时记来源模型 id */
    @Column(name = "fallback_from", length = 64)
    private String fallbackFrom;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(nullable = false)
    @Builder.Default
    private Integer success = 0;

    /** TIMEOUT / HTTP_429 / NO_MODEL / PROVIDER_ERROR ... */
    @Column(name = "error_class", length = 64)
    private String errorClass;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
