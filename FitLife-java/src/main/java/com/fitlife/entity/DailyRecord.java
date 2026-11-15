package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDate;

@Entity
@Table(name = "daily_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "food_intake")
    private Double foodIntake;

    @Column(name = "exercise_burn")
    private Double exerciseBurn;

    @Column(name = "target_calories")
    private Double targetCalories;

    private Double weight;

    // ===== P4：服务端口径的日汇总快照（Dashboard 只读这里，不再由前端自算） =====

    /** 净热量 = 食物摄入 - 运动消耗 */
    @Column(name = "net_calories")
    private Double netCalories;

    /** 当日训练分钟数（来自打卡记录 SUM(duration)） */
    @Column(name = "workout_minutes")
    private Integer workoutMinutes;

    /** 当日完成动作数（status 为 DONE / PARTIAL 的打卡条数，仅计划内打卡） */
    @Column(name = "completed_items")
    private Integer completedItems;

    /** 当日应做动作数（取当天生效计划的明细条数） */
    @Column(name = "planned_items")
    private Integer plannedItems;

    /** 当日实际 RPE 均值 */
    @Column(name = "avg_rpe")
    private Double avgRpe;

    /** 1 = 当日上报过疼痛/不适 */
    @Column(name = "pain_flag")
    private Integer painFlag;

    /** 净热量：有服务端快照用快照，否则按「摄入 − 消耗」兜底（老数据） */
    public double netOrCompute() {
        if (netCalories != null) {
            return netCalories;
        }
        return (foodIntake == null ? 0.0 : foodIntake) - (exerciseBurn == null ? 0.0 : exerciseBurn);
    }
}
