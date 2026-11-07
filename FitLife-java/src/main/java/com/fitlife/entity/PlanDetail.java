package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 训练计划明细：某天某个动作的组次安排
 */
@Entity
@Table(name = "plan_details")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    /** 1-7，表示本周第几天 */
    @Column(name = "day_of_week", nullable = false)
    private Integer dayOfWeek;

    /** 当天训练重点，如 "胸 + 三头" */
    @Column(name = "day_focus", length = 60)
    private String dayFocus;

    @Column(name = "exercise_id")
    private Long exerciseId;

    @Column(name = "exercise_name", length = 60)
    private String exerciseName;

    @Column(name = "muscle_group", length = 30)
    private String muscleGroup;

    private Integer sets;

    @Column(length = 20)
    private String reps;

    @Column(name = "rest_sec")
    private Integer restSec;

    /** 主观强度 RPE 1-10 */
    private Integer rpe;

    /** 当天动作顺序 */
    private Integer seq;

    /** 估算热量消耗 kcal */
    @Column(name = "est_calories")
    private Double estCalories;
}
