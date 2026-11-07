package com.fitlife.dto;

import lombok.Data;

/**
 * 生成训练计划的请求。所有字段均可为空，为空时由用户档案补齐。
 */
@Data
public class GeneratePlanRequest {

    /** 健身目标：减脂 / 增肌 / 塑形 / 体能 */
    private String goal;

    /** 运动经验：初级 / 中级 / 高级 */
    private String experience;

    /** 每周可训练天数 1-7 */
    private Integer weeklyDays;

    /** 单次训练时长（分钟）15-120 */
    private Integer sessionMinutes;

    /** 可用器械，逗号分隔，如 "徒手,哑铃"；为空则视为徒手 */
    private String equipments;

    /** 伤病史 / 禁忌部位，逗号分隔，如 "膝关节,腰椎"；为空表示无 */
    private String injuries;

    /** 是否强制重新生成（忽略缓存） */
    private Boolean force;
}
