package com.fitlife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.dto.*;
import com.fitlife.entity.Exercise;
import com.fitlife.entity.Plan;
import com.fitlife.entity.PlanDetail;
import com.fitlife.entity.UserProfile;
import com.fitlife.repository.ExerciseRepository;
import com.fitlife.repository.PlanDetailRepository;
import com.fitlife.repository.PlanRepository;
import com.fitlife.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 训练计划服务。
 *
 * <p>P2 改造后职责收窄：
 * <ul>
 *   <li>generatePlan 委托 {@link PlanOrchestrator}（图 2 十五步主链路）；</li>
 *   <li>getCurrentPlan/getPlan/listPlans/deletePlan 行为与签名保持不变；</li>
 *   <li>解析 / 模板 / 估算等纯函数统一使用 {@link PlanSupport}，不再各自实现。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanService {

    private final ExerciseRepository exerciseRepository;
    private final PlanRepository planRepository;
    private final PlanDetailRepository planDetailRepository;
    private final UserProfileRepository profileRepository;
    private final PlanOrchestrator planOrchestrator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ============================================================
    // 对外接口
    // ============================================================

    /** 获取当前生效的计划 */
    public ApiResponse getCurrentPlan(Long userId) {
        Optional<Plan> planOpt = planRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, "ACTIVE");
        if (planOpt.isEmpty()) {
            planOpt = planRepository.findFirstByUserIdOrderByIdDesc(userId);
        }
        if (planOpt.isEmpty()) {
            return ApiResponse.success(null);
        }
        return ApiResponse.success(toVO(planOpt.get()));
    }

    /** 历史计划列表 */
    public ApiResponse listPlans(Long userId) {
        List<Plan> plans = planRepository.findByUserIdOrderByIdDesc(userId);
        List<Map<String, Object>> list = plans.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("title", p.getTitle());
            m.put("goal", p.getGoal());
            m.put("weeklyDays", p.getWeeklyDays());
            m.put("createdAt", p.getCreatedAt());
            m.put("status", p.getStatus());
            m.put("aiGenerated", p.getAiGenerated());
            // P2：列表带版本与来源，供前端徽标与回滚入口
            m.put("version", p.getVersion() == null ? 1 : p.getVersion());
            m.put("sourceLink", p.getSourceLink());
            m.put("changeNote", p.getChangeNote());
            return m;
        }).collect(Collectors.toList());
        return ApiResponse.success(list);
    }

    /** 取指定计划详情 */
    public ApiResponse getPlan(Long userId, Long planId) {
        return planRepository.findById(planId)
                .filter(p -> Objects.equals(p.getUserId(), userId))
                .map(p -> ApiResponse.success(toVO(p)))
                .orElseGet(() -> ApiResponse.error("计划不存在"));
    }

    /** 删除计划 */
    @Transactional
    public ApiResponse deletePlan(Long userId, Long planId) {
        Optional<Plan> opt = planRepository.findById(planId)
                .filter(p -> Objects.equals(p.getUserId(), userId));
        if (opt.isEmpty()) {
            return ApiResponse.error("计划不存在");
        }
        planDetailRepository.deleteByPlanId(planId);
        planRepository.delete(opt.get());
        return ApiResponse.success("删除成功");
    }

    /**
     * 生成训练计划：委托 P2 编排主链路（知识检索 → 模板化 Prompt →
     * guarded 模型调用 → 规则校验 → 最小修复 → 版本落库 → 降级兜底）。
     */
    public ApiResponse generatePlan(Long userId, GeneratePlanRequest req) {
        return planOrchestrator.generate(userId, req);
    }

    /**
     * 把指定历史版本设为生效（P2：版本回滚）。
     * 当前 ACTIVE 版本归档，目标版本置 ACTIVE；不产生新版本号，仅切换状态。
     */
    @Transactional
    public ApiResponse activateVersion(Long userId, Long planId) {
        Optional<Plan> target = planRepository.findById(planId)
                .filter(p -> Objects.equals(p.getUserId(), userId));
        if (target.isEmpty()) {
            return ApiResponse.error("计划不存在");
        }
        Plan t = target.get();
        if ("ACTIVE".equals(t.getStatus())) {
            return ApiResponse.success("已是当前生效版本", toVO(t));
        }
        planRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, "ACTIVE")
                .ifPresent(old -> {
                    old.setStatus("ARCHIVED");
                    planRepository.save(old);
                });
        t.setStatus("ACTIVE");
        planRepository.save(t);
        return ApiResponse.success("已回滚到 v" + (t.getVersion() == null ? 1 : t.getVersion()), toVO(t));
    }

    // ============================================================
    // 兼容旧调用：实体转 VO（委托 PlanSupport，含 P2 元信息）
    // ============================================================

    /** 当前生效计划（无 ACTIVE 时取最新一版），供执行闭环模块复用；不存在返回空 */
    public Optional<Plan> currentPlan(Long userId) {
        Optional<Plan> active = planRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, "ACTIVE");
        return active.isPresent() ? active : planRepository.findFirstByUserIdOrderByIdDesc(userId);
    }

    /**
     * 按执行反馈生成下一版（P4 图 4 ⑥）：
     * 走与「重新生成」完全相同的主链路，因此版本链、校验报告、≤10% 容量约束全部生效，
     * 只是把决策理由写进 change_note，便于答辩时说明"为什么加量"。
     */
    @Transactional
    public ApiResponse generateNextVersion(Long userId, String changeNote) {
        GeneratePlanRequest req = new GeneratePlanRequest();
        req.setForce(true);
        currentPlan(userId).ifPresent(plan -> {
            req.setGoal(plan.getGoal());
            req.setWeeklyDays(plan.getWeeklyDays());
            req.setSessionMinutes(plan.getSessionMinutes());
        });
        ApiResponse resp = planOrchestrator.generate(userId, req);
        Object data = resp.getData();
        if (data instanceof PlanVO vo && changeNote != null && vo.getParentId() != null) {
            planRepository.findById(vo.getId()).ifPresent(p -> {
                p.setChangeNote(changeNote.length() > 500 ? changeNote.substring(0, 500) : changeNote);
                planRepository.save(p);
                vo.setChangeNote(p.getChangeNote());
            });
        }
        return resp;
    }

    /** 实体转 VO */
    public PlanVO toVO(Plan plan) {
        List<PlanDetail> details = planDetailRepository
                .findByPlanIdOrderByDayOfWeekAscSeqAsc(plan.getId());
        return PlanSupport.toVO(plan, details, exerciseRepository, objectMapper);
    }
}
