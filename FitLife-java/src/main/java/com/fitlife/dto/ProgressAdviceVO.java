package com.fitlife.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 进阶建议（P4 图 4 ⑤/⑥）。
 *
 * <p>把「循序渐进」从口号变成可执行决定：依据区间内的依从率、实际 RPE 偏差、
 * 疼痛上报，给出 HOLD / DELOAD / PROGRESS / SWAP / STOP 五种决策，
 * 并说明依据与其中需要人工确认的部分。真正生成新版本仍走 P2 主链路
 * （校验器会再次拦截周容量 >10% 的越界）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProgressAdviceVO {

    /** HOLD / DELOAD / PROGRESS / SWAP / STOP */
    private String decision;
    /** 中文标题，如"建议降载 10%" */
    private String title;
    /** 依据（人话，可直接贴到答辩 PPT） */
    private List<String> reasons;
    /** 具体建议动作 */
    private List<String> suggestions;
    /** 建议的周容量变化百分比（正=进阶，负=降载） */
    private Integer volumeChangePct;
    /** 是否可以直接落到生成链路：true 表示可一键按建议重新生成 */
    private Boolean canAutoApply;
    /** 风险等级 INFO / WARN / ERROR */
    private String riskLevel;

    // ===== 计算依据快照（便于解释与复现） =====
    private Integer adherenceRate;
    private Double rpeDeviation;
    private Integer painDays;
    private Integer plannedItems;
    private Integer doneItems;
    private Integer windowDays;
    private Long basePlanId;
    private Integer baseVersion;
    /** 一键进阶后新落库版本的 id（仅 advance 成功时回填，便于前端直接跳转） */
    private Long newPlanId;
    /** 一键进阶后新版本的版本号 */
    private Integer newVersion;
    private String note;
}