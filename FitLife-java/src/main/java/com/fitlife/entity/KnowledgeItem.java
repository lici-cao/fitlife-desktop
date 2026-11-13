package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 运动处方知识条目（P2 知识库）。
 *
 * <p>检索器按目标 / 伤病 / 肌群对 keywords 做关键词打分，命中的条目正文
 * 注入 Prompt 的「运动处方约束」段，并快照进 plans.knowledge_snapshot，
 * 保证「为什么这样排」可溯源。
 */
@Entity
@Table(name = "knowledge_item")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, unique = true)
    private String code;

    /** FITT / HR / PROGRESSION / RECOVERY / CONTRAINDICATION / SUBSTITUTE / TECHNIQUE / POPULATION */
    @Column(nullable = false, length = 24)
    private String type;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 适用目标，逗号分隔；空表示全部目标 */
    @Column(name = "goal_scope", length = 60)
    private String goalScope;

    /** 检索关键词，逗号分隔 */
    @Column(length = 255)
    private String keywords;

    /** INFO / WARN / ERROR，ERROR 级条目参与校验判定 */
    @Column(nullable = false, length = 12)
    @Builder.Default
    private String severity = "INFO";

    @Column(nullable = false)
    @Builder.Default
    private Integer enabled = 1;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
