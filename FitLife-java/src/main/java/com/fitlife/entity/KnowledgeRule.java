package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 可配置校验规则（P2 规则库）。
 *
 * <p>expression 为 JSON 阈值参数，PlanValidator 六类校验器逐条读取；
 * 读不到对应 code 时校验器退回内置默认值，规则表被清空也不会崩。
 */
@Entity
@Table(name = "knowledge_rule")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** HR_TARGET / FITT / PROGRESSION / RECOVERY / CONTRAINDICATION / CAPACITY */
    @Column(nullable = false, length = 64, unique = true)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    /** 对应校验器编号 V1-V6 */
    @Column(nullable = false, length = 32)
    private String category;

    /** JSON 阈值参数 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String expression;

    /** 违反时报告的级别 ERROR / WARN */
    @Column(nullable = false, length = 12)
    @Builder.Default
    private String severity = "ERROR";

    @Column(nullable = false)
    @Builder.Default
    private Integer enabled = 1;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(length = 255)
    private String note;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        updatedAt = LocalDateTime.now();
    }
}
