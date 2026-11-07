package com.fitlife.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话页请求体（对应 /api/chat 与 /api/chat/stream）。
 */
@Data
public class ChatRequest {

    @NotBlank(message = "消息内容不能为空")
    private String message;

    /** 前端选中的模型 id，为空时用后端默认模型 */
    private String model;

    /** 前端会话 id（仅用于日志/埋点，历史由前端本地保存） */
    private String sessionId;

    /** 多轮上下文，按时间正序 */
    private List<Message> history = new ArrayList<>();

    /** 用户档案 / 今日热量等上下文 */
    private Map<String, Object> context;

    /** 是否允许直接落库生成计划（默认 true） */
    private Boolean allowAction = true;

    @Data
    public static class Message {
        /** user / assistant */
        private String role;
        private String content;
    }
}
