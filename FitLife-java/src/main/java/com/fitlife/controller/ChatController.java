package com.fitlife.controller;

import com.fitlife.dto.ApiResponse;
import com.fitlife.dto.ChatRequest;
import com.fitlife.entity.User;
import com.fitlife.service.ChatService;
import com.fitlife.service.LlmGateway;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 对话接口（工作台式对话页的数据源）。
 *
 * <ul>
 *   <li>GET  /api/chat/models  可用模型清单（DeepSeek / 豆包 / 智谱，含是否已配置 Key）</li>
 *   <li>POST /api/chat/stream  流式对话（SSE，逐字返回）</li>
 *   <li>POST /api/chat         非流式对话（接口调试 / 降级用）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final LlmGateway llmGateway;

    private Long getUserId(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user.getId();
        }
        return null;
    }

    /** 可用模型清单：前端模型选择器用这个渲染 */
    @GetMapping("/models")
    public ResponseEntity<ApiResponse> models() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("defaultModel", llmGateway.defaultModelId());
        data.put("available", llmGateway.hasAvailableModel());
        data.put("models", llmGateway.listModels());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /** 流式对话 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication auth, @Valid @RequestBody ChatRequest req) {
        return chatService.stream(getUserId(auth), req);
    }

    /** 非流式对话 */
    @PostMapping
    public ResponseEntity<ApiResponse> chat(Authentication auth, @Valid @RequestBody ChatRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(chatService.chat(getUserId(auth), req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
