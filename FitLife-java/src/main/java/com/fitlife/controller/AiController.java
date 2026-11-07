package com.fitlife.controller;

import com.fitlife.dto.*;
import com.fitlife.service.AiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Validated
public class AiController {

    private final AiService aiService;

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse> chat(@Valid @RequestBody AiChatRequest request) {
        try {
            AiChatResponse response = aiService.chat(request);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/predict-weight")
    public ResponseEntity<ApiResponse> predictWeight(@Valid @RequestBody PredictWeightRequest request) {
        try {
            PredictWeightResponse response = aiService.predictWeight(request);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/diet-suggestion")
    public ResponseEntity<ApiResponse> dietSuggestion(@Valid @RequestBody DietSuggestionRequest request) {
        try {
            DietSuggestionResponse response = aiService.dietSuggestion(request);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/exercise-suggestion")
    public ResponseEntity<ApiResponse> exerciseSuggestion(@Valid @RequestBody ExerciseSuggestionRequest request) {
        try {
            ExerciseSuggestionResponse response = aiService.exerciseSuggestion(request);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/daily-summary")
    public ResponseEntity<ApiResponse> dailySummary(@Valid @RequestBody DailySummaryRequest request) {
        try {
            DailySummaryResponse response = aiService.dailySummary(request);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/sendZp")
    public ResponseEntity<ApiResponse> sendZp(@Valid @RequestBody AiChatRequest request) {
        try {
            if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("消息不能为空"));
            }
            String reply = aiService.chatZp(request.getMessage());
            AiChatResponse response = new AiChatResponse();
            response.setReply(reply);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

}
