package com.fitlife.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import lombok.Data;

/**
 * 训练打卡请求（P4）。
 *
 * <p>两种用法：
 * <ul>
 *   <li>计划内打卡：传 planDetailId（动作名/肌群/计划组次由计划明细自动补齐，
 *       只需上报实际时长、RPE 与是否疼痛）；</li>
 *   <li>自由记录：不传 planDetailId，沿用旧行为（exerciseId + duration + calories）。</li>
 * </ul>
 * status=SKIPPED 时 duration/calories 可为空，skippedReasons 记录原因。
 */
@Data
public class CheckInRequest {

    /** 计划明细 id；为空表示自由记录 */
    private Long planDetailId;

    /** 自由记录的旧字段：动作 id */
    private String exerciseId;

    /** 自由记录的动作名（为空时按 exerciseId 回查动作库，查不到用"运动"） */
    private String exerciseName;

    /** 实际时长（分钟），SKIPPED 时可为空 */
    @Min(value = 0, message = "时长不能为负")
    @Max(value = 600, message = "单次时长不能超过 600 分钟")
    private Integer duration;

    /** 实际消耗热量，为空则按动作 MET 与时长估算 */
    @Min(value = 0, message = "热量不能为负")
    private Double calories;

    /** DONE / SKIPPED / PARTIAL，为空视为 DONE */
    private String status;

    /** 跳过原因，逗号分隔（疼/太累/没时间/器械占用） */
    private String skippedReasons;

    /** 实际主观强度 1-10（计划内打卡的自然输入） */
    @Min(value = 1, message = "RPE 最小为 1")
    @Max(value = 10, message = "RPE 最大为 10")
    private Integer rpe;

    /** 是否上报疼痛/不适 */
    private Boolean pain;

    /** 打卡日期 yyyy-MM-dd，空=今天（用于历史补录与验收脚本） */
    private String date;

    /** 显式指定计划 id，空=当前生效计划（ACTIVE） */
    private Long planId;
}