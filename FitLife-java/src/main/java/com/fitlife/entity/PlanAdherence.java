package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 计划执行依从率快照（P4）。
 *
 * <p>每一次打卡/跳过都在同一事务内重算当天这一行，因此：
 * <ul>
 *   <li>依从率不再由前端 localStorage 计算，服务端唯一口径；</li>
 *   <li>unique(user_id, plan_id, date) 保证一天一条，重复提交只累加不产生脏行；</li>
 *   <li>plan_id 指向具体版本，版本回滚后旧日期的统计仍归属当时的版本。</li>
 * </ul>
 */
@Entity
@Table(name = "plan_adherence")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanAdherence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "planned_items", nullable = false)
    @Builder.Default
    private Integer plannedItems = 0;

    @Column(name = "done_items", nullable = false)
    @Builder.Default
    private Integer doneItems = 0;

    @Column(name = "skipped_items", nullable = false)
    @Builder.Default
    private Integer skippedItems = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer minutes = 0;

    @Column(nullable = false)
    @Builder.Default
    private Double calories = 0.0;

    @Column(name = "avg_rpe")
    private Double avgRpe;

    /** 1 = 当日上报疼痛，进阶链路必须让路 */
    @Column(name = "pain_flag", nullable = false)
    @Builder.Default
    private Integer painFlag = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
        normalize();
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
        normalize();
    }

    private void normalize() {
        if (plannedItems == null) {
            plannedItems = 0;
        }
        if (doneItems == null) {
            doneItems = 0;
        }
        if (skippedItems == null) {
            skippedItems = 0;
        }
        if (minutes == null) {
            minutes = 0;
        }
        if (calories == null) {
            calories = 0.0;
        }
        if (painFlag == null) {
            painFlag = 0;
        }
    }
}