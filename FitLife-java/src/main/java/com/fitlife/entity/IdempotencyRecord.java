package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 幂等记录（P1）。
 *
 * <p>一行代表「一次逻辑提交」：{@code status=0} 处理中，{@code status=1} 已完成。
 * 唯一键 {@code (user_id, idem_key)} 是幂等的最终保证——并发重复请求里
 * 只有一个能插入成功，其余会撞唯一键，由切面改走「回放/409」分支。
 */
@Entity
@Table(
        name = "idempotency_key",
        uniqueConstraints = @UniqueConstraint(name = "uk_idem_user_key", columnNames = {"user_id", "idem_key"}),
        indexes = @Index(name = "idx_idem_created", columnList = "created_at")
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 客户端传入的 Idempotency-Key，最长 128 */
    @Column(name = "idem_key", nullable = false, length = 128)
    private String idemKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 方法 + URI，便于排查「同一个 key 被用在两个接口上」 */
    @Column(nullable = false, length = 160)
    private String endpoint;

    /** 请求体哈希（SHA-256），用于识别「同 key 不同参数」 */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    /** 0=处理中 1=已完成 */
    @Column(nullable = false)
    private Integer status;

    /** 第一次成功的响应体（JSON），回放时原样返回 */
    @Lob
    @Column(name = "response_body", columnDefinition = "LONGTEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}