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

    /**
     * 计划摘要可能较长（大模型生成），这里用 @Lob 交给 Hibernate 按数据库方言
     * 选择 CLOB / TEXT 类型。硬编码 columnDefinition = "TEXT" 在 H2 下会退化成
     * CHARACTER VARYING 长度受限，改用 @Lob 后可同时兼容 H2 与 MySQL。
     */
    @Lob
    @Column(name = "summary")
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
    }
}
