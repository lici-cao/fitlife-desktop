package com.fitlife.controller;

import com.fitlife.dto.*;
import com.fitlife.service.AiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * AI 能力接口。
 *
 * <p>P1 改动：移除每个方法里的 {@code try/catch RuntimeException -> badRequest(e.getMessage())}。
 * 这类写法会把上游厂商的报错原文（含 URL、Key 片段）直接回给浏览器；现在统一由
 * {@code GlobalExceptionHandler} 按异常类型映射状态码与安全文案（429 / 503 / 500）。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Validated
public class AiController {

    private final AiService aiService;

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse> chat(@Valid @RequestBody AiChatRequest request) {
        return ResponseEntity.ok(ApiResponse.success(aiService.chat(request)));
    }

    @PostMapping("/predict-weight")
    public ResponseEntity<ApiResponse> predictWeight(@Valid @RequestBody PredictWeightRequest request) {
        return ResponseEntity.ok(ApiResponse.success(aiService.predictWeight(request)));
    }

    @PostMapping("/diet-suggestion")
    public ResponseEntity<ApiResponse> dietSuggestion(@Valid @RequestBody DietSuggestionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(aiService.dietSuggestion(request)));
    }

    @PostMapping("/exercise-suggestion")
    public ResponseEntity<ApiResponse> exerciseSuggestion(@Valid @RequestBody ExerciseSuggestionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(aiService.exerciseSuggestion(request)));
    }

    @PostMapping("/daily-summary")
    public ResponseEntity<ApiResponse> dailySummary(@Valid @RequestBody DailySummaryRequest request) {
        return ResponseEntity.ok(ApiResponse.success(aiService.dailySummary(request)));
    }

    /** 遗留调试端点：直连智谱。保留但同样受统一鉴权与异常处理约束。 */
    @PostMapping("/sendZp")
    public ResponseEntity<ApiResponse> sendZp(@Valid @RequestBody AiChatRequest request) {
        AiChatResponse response = new AiChatResponse();
        response.setReply(aiService.chatZp(request.getMessage()));
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}