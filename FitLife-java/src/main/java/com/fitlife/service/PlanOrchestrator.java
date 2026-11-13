package com.fitlife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.common.BusinessException;
import com.fitlife.dto.*;
import com.fitlife.entity.Exercise;
import com.fitlife.entity.Plan;
import com.fitlife.entity.PlanDetail;
import com.fitlife.entity.UserProfile;
import com.fitlife.repository.ExerciseRepository;
import com.fitlife.repository.PlanDetailRepository;
import com.fitlife.repository.PlanRepository;
import com.fitlife.repository.UserProfileRepository;
import com.fitlife.util.BodyMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 计划生成编排器（P2 改造：图 2 十五步主链路）。
 *
 * <p>PlanService.generatePlan 委托到这里。与旧链路的区别：
 * <ol>
 *   <li>② 画像补全加入 HRmax/心率储备/靶心率区间（BodyMetrics 静态方法）；</li>
 *   <li>⑤ 知识检索（KnowledgeRetriever）产出约束清单与知识快照；</li>
 *   <li>⑦ Prompt 来自版本化模板（prompt_template，DB 无记录时回退内置正文）；</li>
 *   <li>⑧ LlmGateway.completeGuarded：30s 超时 + 重试 2 次 + 跨厂商转移 + 用量计量；</li>
 *   <li>⑩ 规则校验器六类检查出 ValidationReport；⑪ 最小修复器 ≤2 轮；</li>
 *   <li>⑬ 版本落库：version=MAX+1、parent_id 指向上一版、校验报告与知识快照随行；</li>
 *   <li>⑭ 返回 VO 带 sourceLink/validationReport/riskNotes/changeNote。</li>
 * </ol>
 * 旧端点（/api/plan/chat、/api/plan/match、getCurrentPlan 等）行为不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanOrchestrator {

    private final ExerciseRepository exerciseRepository;
    private final PlanRepository planRepository;
    private final PlanDetailRepository planDetailRepository;
    private final UserProfileRepository profileRepository;
    private final KnowledgeRetriever knowledgeRetriever;
    private final PromptTemplateService promptTemplateService;
    private final PlanValidator planValidator;
    private final PlanRepairer planRepairer;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 校验不通过时最多修复重校轮数（图 2 ⑪ 回环上限） */
    private static final int MAX_REPAIR_ROUNDS = 2;

    /** 内置 Prompt 正文：与 DB 中 prompt_template code=PLAN_GEN version=1 内容一致 */
    private static final String BUILTIN_PLAN_PROMPT = """
        你是健身训练计划编排器。请基于用户画像与候选动作库，编排一份 %weeklyDays% 天/周、每次约 %sessionMinutes% 分钟、目标为「%goal%」（%experience%水平）的训练计划。

        身体指标（已按公式算好，直接引用，不要自行重算）：
        {{metrics}}

        必须遵守的科学约束（来自知识库检索，逐条满足）：
        {{constraints}}

        编排要求：
        1. 训练天数正好 %weeklyDays% 天（dayOfWeek 1-7），其余天休息（items 为空数组）；
        2. 每次 4-6 个动作，顺序：热身 → 主项 → 辅助/核心 → 有氧（减脂日）→ 拉伸；
        3. 减脂目标：每天保留有氧环节，全周有氧合计 ≥150 分钟（中低强度按 20-40 分钟计）；
        4. 增肌目标：同一肌群每周至少 2 次且不连续两天；
        5. 组数：初级 3 组、中级 3-4 组、高级 4-5 组；组间休息：力量 60-120 秒，核心/柔韧 30-60 秒；
        6. RPE 自觉强度 1-10，初级不超过 7，全周平均不超过 8；
        7. exerciseId 必须严格取自候选动作列表中的 id，禁止虚构；
        8. summary 用 2-3 句话说明分化思路；
        9. 只输出 JSON，不要任何解释文字或代码块标记。

        候选动作库（id/name/category/cat/muscle/difficulty/diff/met）：
        %candidatesJson%

        输出 JSON 结构：
        {"title":"计划名","summary":"…","weeklyCardioMin":120,
         "days":[{"dayOfWeek":1,"focus":"胸+三头","items":[
            {"exerciseId":1,"sets":4,"reps":"8-12次","restSec":90,"rpe":7,"seq":1}]}]}
        """;

    /**
     * 生成主链路。返回的 ApiResponse.data = PlanVO（含 P2 元信息）。
     * 复用逻辑与旧版一致：force 非 true 且已有 ACTIVE 计划 → 直接返回现有版本。
     */
    @Transactional
    public ApiResponse generate(Long userId, GeneratePlanRequest req) {
        long started = System.currentTimeMillis();

        // ① 用户提交需求（Controller 已鉴权）；复用检查
        boolean force = Boolean.TRUE.equals(req.getForce());
        if (!force) {
            Optional<Plan> exist = planRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, "ACTIVE");
            if (exist.isPresent()) {
                return ApiResponse.success(toVOWithMeta(exist.get()));
            }
        }

        UserProfile profile = profileRepository.findByUserId(userId).orElse(null);

        // ② 参数校验与画像补全（含心率指标）
        String goal = PlanSupport.firstNonBlank(req.getGoal(),
                profile != null ? planGoal(profile.getGoal()) : null, "保持健康");
        String experience = PlanSupport.firstNonBlank(req.getExperience(),
                profile != null ? profile.getExperience() : null, "初级");
        int weeklyDays = PlanSupport.clamp(req.getWeeklyDays() != null ? req.getWeeklyDays()
                : (profile != null && profile.getWeeklyDays() != null ? profile.getWeeklyDays() : 3), 1, 7);
        int sessionMinutes = PlanSupport.clamp(req.getSessionMinutes() != null ? req.getSessionMinutes()
                : (profile != null && profile.getSessionMinutes() != null ? profile.getSessionMinutes() : 45), 15, 120);
        Set<String> equipments = PlanSupport.parseCsv(PlanSupport.firstNonBlank(req.getEquipments(),
                profile != null ? profile.getEquipments() : null, "徒手"), PlanSupport.EQUIPMENT_WHITELIST);
        Set<String> injuries = PlanSupport.parseCsv(PlanSupport.firstNonBlank(req.getInjuries(),
                profile != null ? profile.getInjuries() : null, ""), null);
        int level = PlanSupport.experienceToLevel(experience);
        double weightKg = profile != null && profile.getWeight() != null ? profile.getWeight() : 65.0;
        int age = profile != null && profile.getAge() != null ? profile.getAge() : 30;
        Integer hrRest = profile != null ? profile.getHrRest() : null;
        String preferredModel = req.getModel();

        // ④ 候选动作结构化筛选（SQL 层难度/器械 + 禁忌预过滤，与旧版一致）
        List<Exercise> rawCandidates = exerciseRepository.findCandidates(level, equipments);
        List<Exercise> candidates = rawCandidates.stream()
                .filter(e -> !PlanSupport.hitContraindication(e, injuries))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            throw new BusinessException(400, "当前器械与经验条件下没有可用动作，请放宽器械条件");
        }
        Map<Long, Exercise> candidateById = candidates.stream()
                .collect(Collectors.toMap(Exercise::getId, e -> e, (a, b) -> a));

        // ⑤ 运动处方知识检索
        List<String> muscles = candidates.stream()
                .map(Exercise::getMuscleGroup).distinct().collect(Collectors.toList());
        KnowledgeRetriever.RetrievalResult retrieval =
                knowledgeRetriever.retrieve(goal, String.join(",", injuries), muscles);

        // ⑥⑦ 约束清单装配 + Prompt 模板版本化渲染
        String metricsText = metricsText(age, hrRest, goal, weightKg);
        String prompt = buildPrompt(goal, experience, weeklyDays, sessionMinutes,
                weightKg, candidates, metricsText, retrieval.getConstraintText());

        // ⑧⑨⑩⑪ 模型生成 → 解析 → 规则校验 → 最小修复（≤2 轮）
        PlanVO vo = null;
        ValidationReport report = null;
        String sourceLink = null;
        String note;
        List<String> repairActions = new ArrayList<>();
        Integer aiTokens = null;
        int attemptsTotal = 0;

        PlanValidator.VContext ctx = new PlanValidator.VContext(age, hrRest, level, goal,
                weeklyDays, sessionMinutes, new ArrayList<>(injuries), weightKg);
        Map<String, Integer> parentSets = parentWeeklySets(userId);

        try {
            LlmGateway.GuardedResult gr = llmGateway.completeGuarded(
                    List.of(Map.of("role", "user", "content", prompt)),
                    0.4, 3000, preferredModel, "PLAN_GENERATE", userId);
            attemptsTotal = gr.attempts();
            aiTokens = gr.totalTokens();
            PlanVO parsed = PlanSupport.parseAiPlan(objectMapper, gr.content(), candidates,
                    weeklyDays, sessionMinutes, weightKg);
            if (parsed != null) {
                parsed.setGoal(goal);
                report = planValidator.validate(parsed, candidateById, ctx, parentSets);
                int rounds = 0;
                while (report != null && !report.isPassed() && rounds < MAX_REPAIR_ROUNDS) {
                    rounds++;
                    List<String> acted = planRepairer.repair(parsed, candidateById, report, ctx);
                    repairActions.addAll(acted);
                    report = planValidator.validate(parsed, candidateById, ctx, parentSets);
                }
                vo = parsed;
                if (rounds > 0) {
                    sourceLink = report.isPassed() ? "LLM_REPAIRED" : "LLM";
                    note = "由大模型编排生成，规则校验" + (report.isPassed() ? "修复后通过" : "发现残留问题（已标注风险提示）");
                } else {
                    sourceLink = "LLM";
                    note = report.isPassed() ? "由大模型编排生成，规则校验通过"
                            : "由大模型编排生成，规则校验存在提示（见校验报告）";
                }
            } else {
                report = null;
                note = "模型返回结构不合规，已使用内置规则模板生成";
            }
        } catch (Exception e) {
            log.warn("AI 生成计划失败，降级为规则模板: {}", e.getMessage());
            note = "AI 服务暂不可用，已使用内置规则模板生成";
        }

        // ⑫ 模板降级：模板计划也过一遍校验器（风险提示仍然产出）
        boolean aiGenerated = vo != null;
        if (vo == null) {
            sourceLink = "TEMPLATE";
            vo = PlanSupport.buildTemplatePlan(goal, level, weeklyDays, sessionMinutes, candidates, weightKg);
            vo.setGoal(goal);
            try {
                report = planValidator.validate(vo, candidateById, ctx, parentSets);
            } catch (Exception e) {
                log.warn("模板计划校验失败（忽略）: {}", e.getMessage());
            }
        }
        vo.setAiGenerated(aiGenerated);
        vo.setNote(note);

        // ⑬ 版本落库
        Plan parent = planRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, "ACTIVE").orElse(null);
        int version = planRepository.findMaxVersion(userId) + 1;
        String changeNote = buildChangeNote(force, parent, repairActions);

        if (parent != null) {
            parent.setStatus("ARCHIVED");
            planRepository.save(parent);
        }

        long generationMs = System.currentTimeMillis() - started;
        Plan plan = Plan.builder()
                .userId(userId)
                .title(vo.getTitle())
                .goal(goal)
                .weeklyDays(weeklyDays)
                .sessionMinutes(sessionMinutes)
                .summary(vo.getSummary())
                .weeklyCardioMin(vo.getWeeklyCardioMin())
                .weeklyCalories(vo.getWeeklyCalories())
                .aiGenerated(aiGenerated)
                .status("ACTIVE")
                .version(version)
                .parentId(parent != null ? parent.getId() : null)
                .changeNote(changeNote)
                .validationReport(writeJson(report))
                .knowledgeSnapshot(retrieval.getSnapshotJson())
                .sourceLink(sourceLink)
                .generationMs((int) generationMs)
                .aiTokens(aiTokens)
                .build();
        planRepository.save(plan);

        List<PlanDetail> details = new ArrayList<>();
        for (PlanDayVO day : vo.getDays()) {
            if (day.getItems() == null) {
                continue;
            }
            for (PlanItemVO item : day.getItems()) {
                details.add(PlanDetail.builder()
                        .planId(plan.getId())
                        .dayOfWeek(day.getDayOfWeek())
                        .dayFocus(day.getFocus())
                        .exerciseId(item.getExerciseId())
                        .exerciseName(item.getExerciseName())
                        .muscleGroup(item.getMuscleGroup())
                        .sets(item.getSets())
                        .reps(item.getReps())
                        .restSec(item.getRestSec())
                        .rpe(item.getRpe())
                        .seq(item.getSeq())
                        .estCalories(item.getEstCalories())
                        .build());
            }
        }
        planDetailRepository.saveAll(details);

        // 明细回填主键：前端打卡必须回传 planDetailId
        fillDetailIds(vo, details);

        // ⑭ 结构化返回
        vo.setId(plan.getId());
        vo.setStatus(plan.getStatus());
        vo.setCreatedAt(plan.getCreatedAt());
        vo.setVersion(version);
        vo.setParentId(plan.getParentId());
        vo.setChangeNote(changeNote);
        vo.setSourceLink(sourceLink);
        vo.setValidationReport(report);
        vo.setRiskNotes(report == null ? List.of() : report.getRiskNotes());
        vo.setGenerationMs((int) generationMs);
        vo.setAiTokens(aiTokens);
        return ApiResponse.success(vo);
    }

    // ============================================================
    // 内部步骤
    // ============================================================

    /** ② 心率与能量指标文本（注入 Prompt 与校验报告 paramsUsed 同源） */
    private String metricsText(int age, Integer hrRest, String goal, double weightKg) {
        int hrMax = BodyMetrics.hrMax(age);
        int rest = BodyMetrics.hrRestOrDefault(hrRest);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("最大心率≈%d 次/分（220−年龄），静息心率取 %d（未实测按默认），心率储备=%d；",
                hrMax, rest, BodyMetrics.hrr(age, hrRest)));
        sb.append(String.format("中强度靶心率 %d–%d 次/分，高强度上限 %d 次/分（Karvonen 法）。",
                BodyMetrics.targetBpm(age, hrRest, 0.50), BodyMetrics.targetBpm(age, hrRest, 0.70),
                BodyMetrics.targetBpm(age, hrRest, 0.90)));
        return sb.toString();
    }

    /** ⑥⑦ 渲染版本化模板；模板服务不可用时退化为内置正文 */
    private String buildPrompt(String goal, String experience, int weeklyDays, int sessionMinutes,
                               double weightKg, List<Exercise> candidates,
                               String metricsText, String constraintText) {
        String candidatesJson;
        try {
            List<Map<String, Object>> brief = new ArrayList<>();
            for (Exercise e : candidates) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getId());
                m.put("name", e.getName());
                m.put("cat", e.getCategory());
                m.put("muscle", e.getMuscleGroup());
                m.put("diff", e.getDifficulty());
                m.put("met", e.getMetValue());
                brief.add(m);
            }
            candidatesJson = objectMapper.writeValueAsString(brief);
        } catch (Exception e) {
            candidatesJson = "[]";
        }
        String body = BUILTIN_PLAN_PROMPT
                .replace("%weeklyDays%", String.valueOf(weeklyDays))
                .replace("%sessionMinutes%", String.valueOf(sessionMinutes))
                .replace("%goal%", goal)
                .replace("%experience%", experience)
                .replace("%candidatesJson%", candidatesJson);
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("metrics", metricsText);
        vars.put("constraints", constraintText == null || constraintText.isBlank()
                ? "· 按通用 FITT 原则编排：频率/强度/时长/类型与用户目标匹配，循序渐进。" : constraintText);
        PromptTemplateService.Rendered rendered =
                promptTemplateService.render("PLAN_GEN", vars, body);
        return rendered.prompt();
    }

    /** 上一版（ACTIVE）的周容量，用于 V3 循序渐进环比 */
    private Map<String, Integer> parentWeeklySets(Long userId) {
        return planRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, "ACTIVE")
                .map(p -> {
                    List<PlanDetail> ds = planDetailRepository
                            .findByPlanIdOrderByDayOfWeekAscSeqAsc(p.getId());
                    Map<String, Integer> sets = new LinkedHashMap<>();
                    for (PlanDetail d : ds) {
                        if (d.getMuscleGroup() == null || d.getSets() == null) {
                            continue;
                        }
                        sets.merge(d.getMuscleGroup(), d.getSets(), Integer::sum);
                    }
                    return sets;
                })
                .orElseGet(() -> {
                    // 无 ACTIVE 但有历史版本时，取最新一版作环比基线
                    List<Plan> all = planRepository.findByUserIdOrderByIdDesc(userId);
                    if (all.isEmpty()) {
                        return Map.of();
                    }
                    Plan p = all.get(0);
                    List<PlanDetail> ds = planDetailRepository
                            .findByPlanIdOrderByDayOfWeekAscSeqAsc(p.getId());
                    Map<String, Integer> sets = new LinkedHashMap<>();
                    for (PlanDetail d : ds) {
                        if (d.getMuscleGroup() == null || d.getSets() == null) {
                            continue;
                        }
                        sets.merge(d.getMuscleGroup(), d.getSets(), Integer::sum);
                    }
                    return sets;
                });
    }

    private String buildChangeNote(boolean force, Plan parent, List<String> repairActions) {
        StringBuilder sb = new StringBuilder();
        sb.append(force ? "用户要求重新生成" : "首次生成");
        if (parent != null) {
            sb.append("；基于 v").append(parent.getVersion() == null ? 1 : parent.getVersion()).append(" 修订");
        }
        if (!repairActions.isEmpty()) {
            sb.append("；自动修复：").append(String.join("、", repairActions.subList(0,
                    Math.min(repairActions.size(), 4))));
        }
        return sb.length() > 500 ? sb.substring(0, 500) : sb.toString();
    }

    private String writeJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    /** 复用现有 ACTIVE 计划时的完整 VO（带版本与校验报告元信息） */
    private PlanVO toVOWithMeta(Plan plan) {
        List<PlanDetail> details = planDetailRepository
                .findByPlanIdOrderByDayOfWeekAscSeqAsc(plan.getId());
        return PlanSupport.toVO(plan, details, exerciseRepository, objectMapper);
    }

    /**
     * 把落库后的明细主键回填到 VO，前端打卡据此提交 planDetailId。
     * 匹配键：dayOfWeek + seq（同一计划内唯一），兜底按动作名 + 出现次序配对。
     */
    static void fillDetailIds(PlanVO vo, List<PlanDetail> details) {
        if (vo == null || vo.getDays() == null || details == null) {
            return;
        }
        java.util.Map<String, java.util.Deque<Long>> byKey = new java.util.HashMap<>();
        for (PlanDetail d : details) {
            if (d.getId() == null) {
                continue;
            }
            String key = d.getDayOfWeek() + "#" + d.getSeq();
            byKey.computeIfAbsent(key, k -> new java.util.ArrayDeque<>()).add(d.getId());
        }
        for (PlanDayVO day : vo.getDays()) {
            if (day.getItems() == null) {
                continue;
            }
            for (PlanItemVO item : day.getItems()) {
                if (item.getPlanDetailId() != null) {
                    continue;
                }
                java.util.Deque<Long> q = byKey.get(day.getDayOfWeek() + "#" + item.getSeq());
                if (q != null && !q.isEmpty()) {
                    item.setPlanDetailId(q.poll());
                }
            }
        }
    }

    private static String planGoal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw) {
            case "fat_loss" -> "减脂";
            case "muscle_gain" -> "增肌";
            case "keep_healthy", "health" -> "保持健康";
            default -> raw;
        };
    }
}
