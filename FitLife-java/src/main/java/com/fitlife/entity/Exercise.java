package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 运动动作库（可检索的知识源，供 AI 编排训练计划时引用）
 */
@Entity
@Table(name = "exercises")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Exercise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    /** 力量 / 有氧 / 核心 / 柔韧 / HIIT */
    @Column(nullable = false, length = 20)
    private String category;

    /** 胸 / 背 / 肩 / 手臂 / 腿 / 臀 / 核心 / 全身 */
    @Column(name = "muscle_group", nullable = false, length = 30)
    private String muscleGroup;

    /** 徒手 / 哑铃 / 杠铃 / 器械 / 弹力带 / 壶铃 */
    @Column(nullable = false, length = 20)
    private String equipment;

    /** 1 初级 2 中级 3 高级 */
    @Column(nullable = false)
    private Integer difficulty;

    /** 代谢当量，用于估算热量消耗 */
    @Column(name = "met_value", nullable = false)
    private Double metValue;

    @Column(name = "default_sets")
    private Integer defaultSets;

    @Column(name = "default_reps", length = 20)
    private String defaultReps;

    @Column(length = 255)
    private String description;

    /** 禁忌症人群，逗号分隔，如 "膝关节,腰椎"；空表示无禁忌 */
    @Column(length = 100)
    private String contraindication;

    // ===== P5 寻源与审核（每条动作必须能点开出处） =====

    /** GUIDELINE / TEXTBOOK / JOURNAL / DATABASE / COMMUNITY */
    @Column(name = "source_type", length = 24)
    private String sourceType;

    /** 来源标题：指南名 / 书名 / 数据库条目 */
    @Column(name = "source_title", length = 180)
    private String sourceTitle;

    /** 可点击的寻源链接 */
    @Column(name = "source_url", length = 255)
    private String sourceUrl;

    /** 章节 / 条目号 / 页码 */
    @Column(name = "source_page", length = 64)
    private String sourcePage;

    /** A 指南与立场声明；B 教材与综述；C 社区经验 */
    @Column(name = "evidence_level", length = 4)
    private String evidenceLevel;

    /** 使用许可与引用说明 */
    @Column(name = "license_note", length = 120)
    private String licenseNote;

    /** APPROVED 已审 / PENDING 待审 / REJECTED 驳回 */
    @Column(name = "review_status", length = 16)
    @Builder.Default
    private String reviewStatus = "APPROVED";

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 255)
    private String reviewNote;

    /** 来源链接最近一次人工核验日期 */
    @Column(name = "last_verified_at")
    private LocalDate lastVerifiedAt;
}
