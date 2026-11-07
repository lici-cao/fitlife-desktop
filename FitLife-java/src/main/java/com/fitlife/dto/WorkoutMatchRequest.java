package com.fitlife.dto;

import lombok.Data;

/**
 * 智能运动搭配请求：从动作库中挑出一组可在指定时间内完成的动作组合。
 */
@Data
public class WorkoutMatchRequest {

    /** 健身目标：减脂 / 增肌 / 塑形 / 体能 */
    private String goal;

    /** 本次训练可用时长（分钟），默认 45 */
    private Integer minutes;

    /** 运动经验：初级 / 中级 / 高级 */
    private String experience;

    /** 可用器械，逗号分隔 */
    private String equipments;

    /** 伤病史 / 禁忌部位，逗号分隔 */
    private String injuries;

    /** 指定重点肌群（可选）：胸 / 背 / 肩 / 手臂 / 腿 / 臀 / 核心 / 全身 */
    private String muscleGroup;

    /** 体重 kg，用于热量估算，为空时取用户档案 */
    private Double weight;
}
