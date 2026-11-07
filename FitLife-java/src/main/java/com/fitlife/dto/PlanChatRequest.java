package com.fitlife.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 对话式生成请求：把自然语言直接转成可落库的训练计划。
 */
@Data
public class PlanChatRequest {

    @NotBlank(message = "消息内容不能为空")
    private String message;

    /** 是否强制重新生成计划（忽略已有生效计划） */
    private Boolean force;
}
