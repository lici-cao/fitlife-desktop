package com.fitlife.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 今日训练卡（P4 图 4 ①）。
 *
 * <p>把「计划」翻译成「今天该做什么」：动作、组次、目标心率区间、
 * 是否已经打卡，以及本日的完成进度。前端运动页与 Dashboard 都读它。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TodayPlanVO {

    private Long planId;
    private Integer version;
    private String planTitle;
    /** 计划内第几天 1-7（按 ISO：周一=1） */
    private Integer planDay;
    private String dayLabel;
    private String focus;
    /** 是否休息日：true 时 items 为空 */
    private Boolean restDay;
    /** 计划内今天应做的动作数 */
    private Integer plannedItems;
    /** 今天已完成（status=DONE/PARTIAL）的动作数 */
    private Integer doneItems;
    /** 依从率 0-100（doneItems / plannedItems）；休息日为 null */
    private Integer adherenceRate;
    /** 今日累计训练分钟 / 热量 */
    private Integer minutes;
    private Double calories;
    /** 目标心率区间（由 P2 的 HRR 参数推导） */
    private Integer hrLow;
    private Integer hrHigh;
    private String hrNote;
    private List<TodayItem> items;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TodayItem {
        /** plan_details.id —— 打卡时回传这个 id */
        private Long planDetailId;
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
        private String contraindication;
        /** 是否已打卡 */
        private Boolean done;
        /** 打卡状态 DONE / SKIPPED / PARTIAL，未打卡为 null */
        private String status;
        /** 实际时长/热量/RPE（来自打卡记录，未打卡为 null） */
        private Integer actualMinutes;
        private Double actualCalories;
        private Integer actualRpe;
        /** 该动作在动作库中的禁忌提示（用户伤病命中时前端标红） */
        private String warningLevel;
        private String warningText;
    }
}