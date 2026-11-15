package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 食物成分条目（P5 饮食方案数据源）。
 *
 * <p>热量统一以 <b>kcal / 100g 可食部</b> 存储，前端展示时按建议克数换算；
 * 同时提供 householdMeasure（1 碗 / 1 个）与 gramsPerUnit，避免用户把
 * 「100g」误读成「一份」。每条数据都带 sourceUrl，可点开核对原始成分表。
 */
@Entity
@Table(name = "food_item")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FoodItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60, unique = true)
    private String name;

    @Column(length = 80)
    private String alias;

    /** 主食 / 蛋白 / 蔬菜 / 水果 / 乳品 / 坚果 / 油脂 / 饮品 */
    @Column(nullable = false, length = 20)
    private String category;

    /** 每 100 克可食部热量（kcal） */
    @Column(name = "kcal_per_100g", nullable = false)
    private Double kcalPer100g;

    @Column(name = "protein_g")
    private Double proteinG;

    @Column(name = "carb_g")
    private Double carbG;

    @Column(name = "fat_g")
    private Double fatG;

    @Column(name = "fiber_g")
    private Double fiberG;

    /** 计量单位符号：g / 个 / 碗 / 杯 / 片 / 勺 / 罐 / 份 / 根 */
    @Column(name = "unit_label", nullable = false, length = 12)
    @Builder.Default
    private String unitLabel = "g";

    /** 常见份量描述，如「1 碗米饭」 */
    @Column(name = "household_measure", length = 32)
    private String householdMeasure;

    /** 1 个计量单位折合克数 */
    @Column(name = "grams_per_unit")
    private Double gramsPerUnit;

    /** CFCT6=中国食物成分表第6版 / USDA=FoodData Central */
    @Column(name = "source_code", length = 24)
    private String sourceCode;

    @Column(name = "source_title", length = 180)
    private String sourceTitle;

    @Column(name = "source_url", length = 255)
    private String sourceUrl;

    @Column(name = "evidence_level", length = 4)
    private String evidenceLevel;

    @Column(nullable = false)
    @Builder.Default
    private Integer verified = 1;

    @Column(nullable = false)
    @Builder.Default
    private Integer enabled = 1;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (unitLabel == null) {
            unitLabel = "g";
        }
    }
}