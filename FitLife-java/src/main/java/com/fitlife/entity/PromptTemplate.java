package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Prompt 模板版本（P2）。
 *
 * <p>同一 code 允许多版本，active=1 的为生效版本；渲染失败或表内无记录时，
 * 编排器退回代码内置模板，保证「模板即数据」而不引入新的单点。
 */
@Entity
@Table(name = "prompt_template",
        uniqueConstraints = @UniqueConstraint(name = "uk_pt_code_version", columnNames = {"code", "version"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromptTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PLAN_GEN / MATCH_GEN / CHAT_SYS ... */
    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(nullable = false, length = 120)
    private String name;

    /** 模板正文，{{key}} 占位符由 PromptTemplateService 渲染 */
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String body;

    /** 同 code 只允许一个 active=1 */
    @Column(nullable = false)
    @Builder.Default
    private Integer active = 0;

    @Column(length = 255)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
