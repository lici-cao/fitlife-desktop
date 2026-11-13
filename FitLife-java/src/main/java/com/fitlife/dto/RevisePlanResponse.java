package com.fitlife.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** P3：对话式修订响应。action 描述本次干了什么；REVISE 时带 diff 与新版本计划。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevisePlanResponse {

    /** GENERATE / REVISE / QUERY / CHAT */
    private String action;

    /** 给用户的自然语言回复（成功文案 / 知识依据 / 降级说明） */
    private String reply;

    /** 新版本计划（GENERATE / REVISE 成功时返回） */
    private PlanVO plan;

    /** 修订差异（仅 REVISE） */
    private List<DiffItem> diff;

    /** 会话标识（前端下次带上可续会话） */
    private String sessionKey;

    /** 意图路由使用了几轮 LLM（false=纯规则，离线可用证明） */
    private Boolean intentByLlm;

    /** 修订未执行的原因（intent=REVISE 但没有可用修订目标等） */
    private String note;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiffItem {
        /** REMOVED / ADDED / MODIFIED */
        private String type;
        /** 周几（如"周三"） */
        private String dayLabel;
        /** 受影响动作名 */
        private String exerciseName;
        /** 人话描述，如"深蹲 → 腿举（膝关节禁忌替换）" */
        private String detail;
    }
}
