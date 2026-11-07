package com.fitlife.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 训练计划中的一天
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanDayVO {
    /** 1-7 */
    private Integer dayOfWeek;
    /** 星期几中文，如 "周一" */
    private String dayLabel;
    /** 训练重点，如 "胸 + 三头"；休息日为空 */
    private String focus;
    /** 是否休息日 */
    private Boolean restDay;
    /** 当天总时长估算（分钟） */
    private Integer estMinutes;
    /** 当天热量估算 kcal */
    private Double estCalories;
    private List<PlanItemVO> items;
}
