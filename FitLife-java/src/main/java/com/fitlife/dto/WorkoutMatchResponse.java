package com.fitlife.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 智能运动搭配结果：一组带组次、要领、热量估算的真实动作卡。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkoutMatchResponse {

    /** 搭配思路说明（AI 生成，失败时为规则说明） */
    private String summary;

    /** 训练阶段说明，如「热身 → 主项 → 辅助 → 核心 → 有氧 → 拉伸」 */
    private String focus;

    private Integer totalMinutes;
    private Double estCalories;
    private Boolean aiGenerated;
    private String note;

    private List<MatchedExercise> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MatchedExercise {
        /** 动作在动作库中的真实 id，前端可据此跳转动作详情 */
        private Long exerciseId;
        private String name;
        private String category;
        private String muscleGroup;
        private String equipment;
        private Integer difficulty;
        private Double metValue;
        /** 热身 / 主项 / 辅助 / 核心 / 有氧 / 拉伸 */
        private String phase;
        private Integer sets;
        private String reps;
        private Integer restSec;
        private Integer minutes;
        private Double estCalories;
        private String description;
        private String contraindication;
    }
}
