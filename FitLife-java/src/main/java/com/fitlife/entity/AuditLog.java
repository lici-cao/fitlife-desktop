package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 操作/安全审计日志（P1）。
 *
 * <p>只记录「谁在什么时候对哪个资源做了什么」，不记录业务正文，
 * 避免把身高体重、伤病等敏感信息重复落库。
 * 当前由归属校验失败、管理员接口访问写入；P5 后台管理会复用同一张表。
 */
@Entity
@Table(
        name = "audit_log",
        indexes = {
                @Index(name = "idx_audit_user", columnList = "user_id"),
                @Index(name = "idx_audit_created", columnList = "created_at")
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 64)
    private String username;

    /** 动作：PLAN_VIEW_DENIED / PLAN_DELETE_DENIED / ADMIN_USERS_VIEW ... */
    @Column(nullable = false, length = 64)
    private String action;

    /** 资源类型：PLAN / CUSTOM_FOOD / USER */
    @Column(length = 32)
    private String target;

    @Column(name = "target_id")
    private Long targetId;

    @Column(length = 512)
    private String detail;

    @Column(length = 64)
    private String ip;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}