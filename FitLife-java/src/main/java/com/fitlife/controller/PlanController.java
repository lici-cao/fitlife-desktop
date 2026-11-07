package com.fitlife.controller;

import com.fitlife.dto.*;
import com.fitlife.entity.User;
import com.fitlife.service.AiPlanService;
import com.fitlife.service.PlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 训练计划接口
 */
@RestController
@RequestMapping("/api/plan")
@RequiredArgsConstructor
public class PlanController {

    private final PlanService planService;
    private final AiPlanService aiPlanService;

    private Long getUserId(Authentication auth) {
        return ((User) auth.getPrincipal()).getId();
    }

    /** 获取当前生效计划 */
    @GetMapping("/current")
    public ResponseEntity<ApiResponse> current(Authentication auth) {
        return ResponseEntity.ok(planService.getCurrentPlan(getUserId(auth)));
    }

    /** 历史计划列表 */
    @GetMapping("/list")
    public ResponseEntity<ApiResponse> list(Authentication auth) {
        return ResponseEntity.ok(planService.listPlans(getUserId(auth)));
    }

    /** 计划详情 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse> detail(Authentication auth, @PathVariable Long id) {
        return ResponseEntity.ok(planService.getPlan(getUserId(auth), id));
    }

    /** 生成训练计划 */
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse> generate(Authentication auth,
                                                @RequestBody(required = false) GeneratePlanRequest req) {
        try {
            return ResponseEntity.ok(planService.generatePlan(getUserId(auth),
                    req == null ? new GeneratePlanRequest() : req));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    /** 删除计划 */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> delete(Authentication auth, @PathVariable Long id) {
        return ResponseEntity.ok(planService.deletePlan(getUserId(auth), id));
    }

    /**
     * 对话式生成：把自然语言直接转成可落库的训练计划 / 运动搭配。
     * 这是 AI 搭子从「只会聊天」升级为「能办事」的入口。
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse> chat(Authentication auth, @RequestBody PlanChatRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    aiPlanService.chat(getUserId(auth), req.getMessage(), req.getForce())));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    /** 智能运动搭配：从动作库挑一组带组次与热量估算的真实动作 */
    @PostMapping("/match")
    public ResponseEntity<ApiResponse> match(Authentication auth,
                                             @RequestBody(required = false) WorkoutMatchRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(aiPlanService.match(getUserId(auth),
                    req == null ? new WorkoutMatchRequest() : req)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
