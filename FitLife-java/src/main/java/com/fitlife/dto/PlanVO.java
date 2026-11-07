package com.fitlife.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 训练计划完整响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanVO {
    private Long id;
    private String title;
    private String goal;
    private Integer weeklyDays;
    private Integer sessionMinutes;
    private String summary;
    private Integer weeklyCardioMin;
    private Double weeklyCalories;
    /** true = 大模型生成；false = 规则模板兜底 */
    private Boolean aiGenerated;
    /** 生成说明 / 降级原因 */
    private String note;
    private String status;
    private LocalDateTime createdAt;
    private List<PlanDayVO> days;
}
