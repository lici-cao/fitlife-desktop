package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "users",
        indexes = @Index(name = "idx_users_role", columnList = "role")
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    /** 普通用户角色 */
    public static final String ROLE_USER = "USER";
    /** 管理员角色（后台管理、审计日志） */
    public static final String ROLE_ADMIN = "ADMIN";

    /** 账号正常 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 账号禁用：登录与鉴权都会被拒绝 */
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    /** 角色：USER / ADMIN（P1 RBAC） */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String role = ROLE_USER;

    /** 状态：ACTIVE / DISABLED */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = STATUS_ACTIVE;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (role == null) {
            role = ROLE_USER;
        }
        if (status == null) {
            status = STATUS_ACTIVE;
        }
    }
}