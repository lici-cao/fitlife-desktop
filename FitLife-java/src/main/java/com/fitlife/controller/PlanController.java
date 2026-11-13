package com.fitlife.controller;

import com.fitlife.common.BusinessException;
import com.fitlife.common.annotation.Idempotent;
import com.fitlife.common.annotation.OwnershipCheck;
import com.fitlife.dto.*;
import com.fitlife.entity.User;
import com.fitlife.service.AiPlanService;
import com.fitlife.service.PlanService;
import com.fitlife.service.PlanRevisionService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 训练计划接口。
 *
 * <p>P1 改动：
 * <ul>
 *   <li>去掉 try/catch RuntimeException + 回传 e.getMessage() 的写法，交给全局异常处理器统一收口；</li>
 *   <li>{@code /generate}、{@code /chat} 加 {@link Idempotent}：同一个 Idempotency-Key 的重复点击/网络重试
 *       只会落一份计划，第二次直接回放第一次的响应；</li>
 *   <li>{@code /{id}} 系列加 {@link OwnershipCheck}：进入方法前就判定归属，越权 403 + 审计。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/plan")
@RequiredArgsConstructor
public class PlanController {

    private final PlanService planService;
    private final AiPlanService aiPlanService;
    private final PlanRevisionService planRevisionService;

    private Long userId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof User user) || user.getId() == null) {
            throw new BusinessException(401, "未登录或登录已过期");
        }
        return user.getId();
    }

    /** 获取当前生效计划 */
    @GetMapping("/current")
    public ResponseEntity<ApiResponse> current(Authentication auth) {
        return ResponseEntity.ok(planService.getCurrentPlan(userId(auth)));
    }

    /** 历史计划列表 */
    @GetMapping("/list")
    public ResponseEntity<ApiResponse> list(Authentication auth) {
        return ResponseEntity.ok(planService.listPlans(userId(auth)));
    }

    /** 计划详情（归属校验） */
    @GetMapping("/{id}")
    @OwnershipCheck(type = OwnershipCheck.ResourceType.PLAN, idParam = "id")
    public ResponseEntity<ApiResponse> detail(Authentication auth, @PathVariable Long id) {
        return ResponseEntity.ok(planService.getPlan(userId(auth), id));
    }

    /** 生成训练计划（幂等） */
    @PostMapping("/generate")
    @Idempotent
    public ResponseEntity<ApiResponse> generate(Authentication auth,
                                                @RequestBody(required = false) GeneratePlanRequest req) {
        return ResponseEntity.ok(planService.generatePlan(userId(auth),
                req == null ? new GeneratePlanRequest() : req));
    }

    /** 删除计划（归属校验） */
    @DeleteMapping("/{id}")
    @OwnershipCheck(type = OwnershipCheck.ResourceType.PLAN, idParam = "id")
    public ResponseEntity<ApiResponse> delete(Authentication auth, @PathVariable Long id) {
        return ResponseEntity.ok(planService.deletePlan(userId(auth), id));
    }

    /** 版本回滚（P2）：把历史版本设为生效，当前版本归档；归属校验 */
    @PostMapping("/{id}/activate")
    @OwnershipCheck(type = OwnershipCheck.ResourceType.PLAN, idParam = "id")
    public ResponseEntity<ApiResponse> activate(Authentication auth, @PathVariable Long id) {
        return ResponseEntity.ok(planService.activateVersion(userId(auth), id));
    }

    /**
     * P3：对话式局部修订（图 3 V 分支）。
     * 会话上下文 + LLM/规则意图路由 → 只改目标动作 → 结构化 Diff + v(n+1) 版本落库。
     * 同一 Idempotency-Key 重复提交只落一次修订。
     */
    @PostMapping("/revise")
    @Idempotent
    public ResponseEntity<ApiResponse> revise(Authentication auth, @Valid @RequestBody RevisePlanRequest req) {
        return ResponseEntity.ok(ApiResponse.success(
                planRevisionService.handle(userId(auth), req)));
    }

    /**
     * 对话式生成：把自然语言直接转成可落库的训练计划 / 运动搭配。
     * 这是 AI 搭子从「只会聊天」升级为「能办事」的入口。
     */
    @PostMapping("/chat")
    @Idempotent
    public ResponseEntity<ApiResponse> chat(Authentication auth, @RequestBody PlanChatRequest req) {
        return ResponseEntity.ok(ApiResponse.success(
                aiPlanService.chat(userId(auth), req.getMessage(), req.getForce())));
    }

    /** 智能运动搭配：从动作库挑一组带组次与热量估算的真实动作 */
    @PostMapping("/match")
    public ResponseEntity<ApiResponse> match(Authentication auth,
                                             @RequestBody(required = false) WorkoutMatchRequest req) {
        return ResponseEntity.ok(ApiResponse.success(aiPlanService.match(userId(auth),
                req == null ? new WorkoutMatchRequest() : req)));
    }
}