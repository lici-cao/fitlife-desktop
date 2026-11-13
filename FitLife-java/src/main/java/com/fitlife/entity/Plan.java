package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 训练计划主表
 */
@Entity
@Table(name = "plans")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Plan {

    /** 计划状态：生效中 / 已归档（历史版本） */
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 120)
    private String title;

    @Column(length = 30)
    private String goal;

    @Column(name = "weekly_days")
    private Integer weeklyDays;

    @Column(name = "session_minutes")
    private Integer sessionMinutes;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "weekly_cardio_min")
    private Integer weeklyCardioMin;

    @Column(name = "weekly_calories")
    private Double weeklyCalories;

    /** true 表示由大模型生成，false 表示规则模板兜底 */
    @Column(name = "ai_generated")
    private Boolean aiGenerated;

    @Column(length = 20)
    private String status;

    // ===== P2 版本链与校验证据 =====

    /** 同用户内递增版本号（配合 uk(user_id, version)） */
    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    /** 上一版本计划 id，回滚与对比锚点 */
    @Column(name = "parent_id")
    private Long parentId;

    /** 本版变更说明：修复动作清单 / 降级原因 */
    @Column(name = "change_note", length = 512)
    private String changeNote;

    /** 规则校验报告 JSON（ValidationReport 序列化） */
    @Column(name = "validation_report", columnDefinition = "LONGTEXT")
    private String validationReport;

    /** 生成时注入的知识条目/规则快照 JSON，保证可溯源 */
    @Column(name = "knowledge_snapshot", columnDefinition = "LONGTEXT")
    private String knowledgeSnapshot;

    /** LLM=大模型直接通过；LLM_REPAIRED=修复后通过；TEMPLATE=规则模板兜底 */
    @Column(name = "source_link", length = 16)
    private String sourceLink;

    /** 本次生成端到端耗时（毫秒） */
    @Column(name = "generation_ms")
    private Integer generationMs;

    /** 本次生成消耗 token 总数 */
    @Column(name = "ai_tokens")
    private Integer aiTokens;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "ACTIVE";
        }
        if (version == null) {
            version = 1;
        }
    }
}
