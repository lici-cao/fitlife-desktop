package com.fitlife.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 训练计划明细项（某天的一个动作）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanItemVO {
    private Long exerciseId;
    private String exerciseName;
    private String muscleGroup;
    private String category;
    private Integer sets;
    private String reps;
    private Integer restSec;
    private Integer rpe;

    /** 计划明细主键：前端打卡（POST /api/data/check-in）必须回传该值 */
    private Long planDetailId;
    private Integer seq;
    private Double estCalories;
    private String description;

    // ===== P5 寻源：每个动作都能点开出处 =====

    /** 来源类型：GUIDELINE / TEXTBOOK / JOURNAL / DATABASE / COMMUNITY */
    private String sourceType;
    /** 来源标题，如「ACSM 运动测试与运动处方指南（第11版）」 */
    private String sourceTitle;
    /** 可点击寻源链接 */
    private String sourceUrl;
    /** 章节 / 条目号 */
    private String sourcePage;
    /** A / B / C 证据等级 */
    private String evidenceLevel;
    /** 禁忌人群提示，如「膝关节」 */
    private String contraindication;
}
