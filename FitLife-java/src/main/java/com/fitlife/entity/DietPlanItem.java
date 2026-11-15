package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 计划附带的饮食明细（某天某餐的一项食物）。
 *
 * <p>与训练明细分开存表：训练要支持打卡（plan_details 有 rpe/组次），
 * 饮食只需展示与核对，混在一张表里会让打卡统计把食物当成动作。
 */
@Entity
@Table(name = "diet_plan_item")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DietPlanItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "day_of_week", nullable = false)
    private Integer dayOfWeek;

    /** 早餐 / 午餐 / 晚餐 / 加餐 */
    @Column(nullable = false, length = 12)
    private String meal;

    @Column(name = "food_id")
    private Long foodId;

    @Column(name = "food_name", nullable = false, length = 60)
    private String foodName;

    /** 建议摄入克数 */
    @Column(nullable = false)
    private Double grams;

    @Column(name = "unit_label", length = 12)
    private String unitLabel;

    /** 份量描述，如「1 碗(150g)」 */
    @Column(name = "amount_desc", length = 48)
    private String amountDesc;

    @Column(nullable = false)
    private Double kcal;

    @Column(name = "protein_g")
    private Double proteinG;

    @Column(name = "carb_g")
    private Double carbG;

    @Column(name = "fat_g")
    private Double fatG;

    private Integer seq;

    /** 该食物数据的来源链接，前端直接展示 */
    @Column(name = "source_url", length = 255)
    private String sourceUrl;
}