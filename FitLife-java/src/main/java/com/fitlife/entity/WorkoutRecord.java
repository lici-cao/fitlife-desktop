package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDate;

@Entity
@Table(name = "workout_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkoutRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "exercise_id", nullable = false)
    private String exerciseId;

    @Column(name = "exercise_name")
    private String exerciseName;

    // ===== P4：打卡与计划明细关联 =====

    /** 关联 plan_details.id；为空表示自由记录（不参与依从率） */
    @Column(name = "plan_detail_id")
    private Long planDetailId;

    /** 计划内第几天 1-7，冗余保存便于按日聚合 */
    @Column(name = "plan_day")
    private Integer planDay;

    @Column(nullable = false)
    private Integer duration;

    @Column(name = "calories_burned")
    private Double caloriesBurned;

    /** DONE / SKIPPED / PARTIAL；旧数据与自由记录视为 DONE */
    @Column(length = 12)
    @Builder.Default
    private String status = "DONE";

    /** 跳过原因，逗号分隔（疼/太累/没时间/器械占用），仅 SKIPPED 时有意义 */
    @Column(name = "skipped_reasons", length = 120)
    private String skippedReasons;

    /** 实际主观强度（用户上报），用于与计划 RPE 对比 */
    private Integer rpe;

    /** 打卡时的疼痛标记（1 表示当天有不适上报） */
    @Column(name = "pain_flag")
    @Builder.Default
    private Integer painFlag = 0;
}
