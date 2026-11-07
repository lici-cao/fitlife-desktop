package com.fitlife.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 搭子对话响应：除文本外，还可以携带一份已落库的计划或一组动作搭配。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanChatResponse {

    /** PLAN_CREATED=已生成周计划 / MATCH=返回运动搭配 / CHAT=普通对话 */
    private String action;

    private String reply;

    /** action=PLAN_CREATED 时有值 */
    private PlanVO plan;

    /** action=MATCH 时有值 */
    private WorkoutMatchResponse match;
}
