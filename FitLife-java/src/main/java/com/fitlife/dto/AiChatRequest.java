package com.fitlife.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.Map;

@Data
public class AiChatRequest {
    @NotBlank(message = "消息内容不能为空")
    private String message;

    /** 指定模型，为空时使用 ai.default-model */
    private String model;

    private Map<String, Object> context;
}