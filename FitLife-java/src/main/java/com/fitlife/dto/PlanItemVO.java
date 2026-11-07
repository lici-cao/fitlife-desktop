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
    private Integer seq;
    private Double estCalories;
    private String description;
}
