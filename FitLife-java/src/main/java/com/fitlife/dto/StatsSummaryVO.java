package com.fitlife.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统计汇总（P4 图 4 ④）。
 *
 * <p>一次请求返回 Dashboard 需要的全部聚合值，口径统一在服务端，
 * 前端不再用 localStorage 自己算。所有比率都在服务端算好，避免多端不一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StatsSummaryVO {

    /** 统计区间（含端点） */
    private String startDate;
    private String endDate;
    /** 区间天数 */
    private Integer days;
    /** 有打卡记录的天数 */
    private Integer activeDays;

    // ===== 依从率 =====
    /** 计划内应做动作数（区间内、只算有计划的日期） */
    private Integer plannedItems;
    /** 已完成动作数 */
    private Integer doneItems;
    /** 跳过动作数 */
    private Integer skippedItems;
    /** 依从率 = done / planned × 100，无计划日期时为 null */
    private Integer adherenceRate;
    /** 连续达标天数（依从率 ≥ 80% 的连续历史，截至 endDate） */
    private Integer streakDays;

    // ===== 训练量 =====
    private Integer totalMinutes;
    private Integer totalSessions;
    private Double totalBurnCalories;
    private Double totalIntakeCalories;
    /** 净热量 = 摄入 - 消耗 */
    private Double netCalories;
    /** 日均净热量（只算有记录的天） */
    private Double avgNetCalories;
    /** 周均训练分钟 */
    private Integer avgWeeklyMinutes;

    // ===== 强度与偏差 =====
    /** 实际 RPE 均值 */
    private Double avgActualRpe;
    /** 计划 RPE 均值 */
    private Double avgPlannedRpe;
    /** 偏差 = 实际 - 计划；> 0 表示比计划更累 */
    private Double rpeDeviation;
    /** 疼痛上报天数 */
    private Integer painDays;

    // ===== 趋势（画图用） =====
    private List<DailyPoint> series;

    // ===== 风险提示（分级） =====
    private List<RiskNote> risks;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DailyPoint {
        private String date;
        private Integer plannedItems;
        private Integer doneItems;
        private Integer adherenceRate;
        private Integer minutes;
        private Double burn;
        private Double intake;
        private Double net;
        private Double avgRpe;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RiskNote {
        /** INFO / WARN / ERROR */
        private String level;
        private String code;
        private String message;
        /** 建议动作，如"本周降载 10%" */
        private String suggestion;
    }
}