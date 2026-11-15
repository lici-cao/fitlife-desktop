package com.fitlife.dto;

import lombok.Data;

/**
 * 每日记录 DTO —— P4 在原有四个字段基础上追加服务端口径的汇总值。
 *
 * <p>前端旧字段（foodIntake / exerciseBurn / targetCalories / weight）保持原样，
 * 因此 Dashboard 可以直接从 localStorage 切换到接口而不改模板。
 */
@Data
public class DailyRecordDTO {
    private String date;
    private Double foodIntake;
    private Double exerciseBurn;
    private Double targetCalories;
    private Double weight;

    // ===== P4 新增（服务端聚合，前端只展示） =====
    private Double netCalories;
    private Integer workoutMinutes;
    private Integer completedItems;
    private Integer plannedItems;
    private Integer adherenceRate;
    private Double avgRpe;
    private Boolean pain;
}