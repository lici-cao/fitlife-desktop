package com.fitlife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.common.BusinessException;
import com.fitlife.dto.*;
import com.fitlife.entity.*;
import com.fitlife.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * P3：对话式计划修订（图 3 阶段二 V 分支）。
 * 局部修订四步：解析修订约束 → 只定位受影响的动作（Diff）→ 增量校验 → 生成 v(n+1)
 * （parent_id 指向旧版，change_note 记录用户指令，source_link=REVISED），并返回结构化 Diff。
 *
 * 设计约束：
 * - 修订建立在当前 ACTIVE 计划之上，其他动作不动；
 * - 增量校验以「修订前周组数」为 V3 环比基线（修订本身不应触发循序渐进误报），
 *   其余 V1/V4/V5/V6 全量复检，新增违例只提示不阻断（用户意图优先）；
 * - 无 Key 环境整链路走规则（IntentRouter 已兜底），修订解析/替代选择全部本地规则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanRevisionService {

    private final PlanRepository planRepository;
    private final PlanDetailRepository planDetailRepository;
    private final ExerciseRepository exerciseRepository;
    private final UserProfileRepository userProfileRepository;
    private final PlanValidator planValidator;
    private final KnowledgeRetriever knowledgeRetriever;
    private final IntentRouter intentRouter;
    private final ChatSessionService chatSessionService;
    private final PlanService planService;
    private final ObjectMapper objectMapper;

    /** 对话式修订入口：意图路由 + 三分支处理。 */
    @Transactional
    public RevisePlanResponse handle(Long userId, RevisePlanRequest req) {
        // 会话（可空，持久化不可用不阻断）
        ChatSession session = chatSessionService.resolveSession(userId, req.getSessionKey(), req.getMessage());

        // 上下文装配 + 意图路由
        boolean hasActive = planRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, "ACTIVE").isPresent();
        String context = chatSessionService.assembleContext(userId, session);
        IntentRouter.RouteResult route = intentRouter.route(userId, req.getMessage(), context, hasActive);

        boolean allowAction = !Boolean.FALSE.equals(req.getAllowAction());
        if (!allowAction && (IntentRouter.GENERATE.equals(route.intent())
                || IntentRouter.REVISE.equals(route.intent()))) {
            route = IntentRouter.RouteResult.of(IntentRouter.CHAT);
        }

        RevisePlanResponse resp;
        switch (route.intent()) {
            case IntentRouter.GENERATE -> resp = doGenerate(userId, req, session, route);
            case IntentRouter.REVISE -> resp = doRevise(userId, req, session, route);
            case IntentRouter.QUERY -> resp = doQuery(userId, session);
            default -> resp = doChat();
        }
        resp.setSessionKey(session != null ? String.valueOf(session.getId()) : req.getSessionKey());
        resp.setIntentByLlm(route.usedLlm());

        // 留痕（失败仅告警）
        chatSessionService.appendMessage(session, ChatSession.ROLE_USER, req.getMessage(), route.intent(), null);
        chatSessionService.appendMessage(session, ChatSession.ROLE_ASSISTANT,
                resp.getReply(), route.intent(),
                resp.getPlan() != null ? "planId=" + resp.getPlan().getId() + ",v" + resp.getPlan().getVersion() : null);
        return resp;
    }

    // ---------------- GENERATE：委托主链路 ----------------
    private RevisePlanResponse doGenerate(Long userId, RevisePlanRequest req, ChatSession session,
                                          IntentRouter.RouteResult route) {
        GeneratePlanRequest g = new GeneratePlanRequest();
        g.setForce(true); // 对话说"重新生成"即明确要求新版本
        if (req.getModel() != null && !req.getModel().isBlank()) g.setModel(req.getModel());
        ApiResponse api = planService.generatePlan(userId, g);
        if (api.getCode() >= 400) {
            return RevisePlanResponse.builder().action("REVISE_FAILED")
                    .reply(api.getMessage() == null ? "生成失败" : api.getMessage()).build();
        }
        PlanVO vo = (PlanVO) api.getData();
        chatSessionService.touchSession(session, vo.getId());
        return RevisePlanResponse.builder().action("GENERATE")
                .reply("已按当前画像重新生成 v" + vo.getVersion() + "，来源 " + sourceName(vo.getSourceLink())
                        + "。校验得分 " + (vo.getValidationReport() != null ? vo.getValidationReport().getScore() : "—")
                        + "。可以继续说「把X换成Y」做局部调整。")
                .plan(vo).build();
    }

    // ---------------- REVISE：局部修订核心 ----------------
    private RevisePlanResponse doRevise(Long userId, RevisePlanRequest req, ChatSession session,
                                        IntentRouter.RouteResult route) {
        Optional<Plan> activeOpt = planRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, "ACTIVE");
        if (activeOpt.isEmpty()) {
            return RevisePlanResponse.builder().action("QUERY")
                    .reply("当前还没有生效的计划，先说「给我生成一份计划」吧。").build();
        }
        Plan active = activeOpt.get();
        List<PlanDetail> details = planDetailRepository.findByPlanIdOrderByDayOfWeekAscSeqAsc(active.getId());
        PlanVO vo = PlanSupport.toVO(active, details, exerciseRepository, objectMapper);
        // 修订前快照（Diff 基线 + V3 环比基线）
        Map<String, Integer> oldSnapshot = snapshot(vo);
        Map<String, String> oldNameToMuscle = nameToMuscle(vo);

        List<RevisePlanResponse.DiffItem> diff = new ArrayList<>();
        boolean changed = false;
        String opNote = "";
        UserProfile prof = userProfileRepository.findByUserId(userId).orElse(null);
        switch (route.operation() == null ? "" : route.operation()) {
            case IntentRouter.OP_REPLACE -> {
                diff.addAll(applyReplace(vo, route, prof));
                changed = !diff.isEmpty();
                opNote = diff.isEmpty() ? "没有找到可替换的目标动作" : "替换 " + diff.size() + " 处";
            }
            case IntentRouter.OP_ADJUST_RPE -> {
                diff.addAll(applyAdjustRpe(vo, route));
                changed = !diff.isEmpty();
                opNote = "强度调整 " + diff.size() + " 处";
            }
            case IntentRouter.OP_REMOVE -> {
                diff.addAll(applyRemove(vo, route, prof));
                changed = !diff.isEmpty();
                opNote = "移除 " + diff.size() + " 处";
            }
            default -> {
                // 意图是修订但没解析出操作（LLM 失败且规则没匹配）：按泛化替换处理
                diff.addAll(applyReplace(vo, route, prof));
                changed = !diff.isEmpty();
                opNote = diff.isEmpty() ? "没有找到可修订的目标" : "替换 " + diff.size() + " 处";
            }
        }

        if (!changed) {
            return RevisePlanResponse.builder().action("REVISE_SKIPPED")
                    .reply("没有定位到需要修改的动作。可以更具体一些，例如「把周三的深蹲换成臀桥」或「强度降一点」。")
                    .note(opNote).build();
        }

        // 增量重算 + 校验（以修订前周组数为 V3 基线，避免修订误报循序渐进）
        PlanSupport.reSummarizeWeek(vo);
        ValidationReport report = null;
        try {
            PlanValidator.VContext ctx = buildContext(vo, prof);
            Map<String, Integer> parentSets = oldWeeklySets(oldSnapshot, oldNameToMuscle);
            Map<Long, Exercise> candidateById = loadCandidatesFor(vo);
            report = planValidator.validate(vo, candidateById, ctx, parentSets);
        } catch (Exception e) {
            log.warn("修订后校验失败（忽略，保留修订）: {}", e.getMessage());
        }

        // v(n+1) 落库（source_link=REVISED）
        Plan saved = persistAsNewVersion(userId, active, vo, report,
                "用户修订：" + truncate(req.getMessage(), 200));

        vo.setId(saved.getId());
        vo.setStatus(saved.getStatus());
        vo.setCreatedAt(saved.getCreatedAt());
        vo.setVersion(saved.getVersion());
        vo.setParentId(saved.getParentId());
        vo.setChangeNote(saved.getChangeNote());
        vo.setSourceLink("REVISED");
        vo.setValidationReport(report);
        if (report != null) vo.setRiskNotes(report.getRiskNotes());

        chatSessionService.touchSession(session, saved.getId());

        StringBuilder reply = new StringBuilder();
        reply.append("已按你的要求局部修订为 v").append(saved.getVersion())
                .append("（基于 v").append(saved.getParentId() == null ? 1 : saved.getVersion() - 1)
                .append("，其余动作未动）：\n");
        for (RevisePlanResponse.DiffItem d : diff) {
            reply.append("· ").append(d.getDayLabel()).append(" ").append(d.getDetail()).append('\n');
        }
        if (report != null && !report.isPassed()) {
            reply.append("注意：修订引入 ").append(countLevel(report, "ERROR")).append(" 项硬性违例，")
                    .append(countLevel(report, "WARN")).append(" 项提示（详见校验报告）。\n");
        }
        return RevisePlanResponse.builder().action("REVISE")
                .reply(reply.toString()).plan(vo).diff(diff).note(opNote).build();
    }

    // ---------------- QUERY：解释与溯源（离线可靠） ----------------
    private RevisePlanResponse doQuery(Long userId, ChatSession session) {
        Optional<Plan> activeOpt = planRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, "ACTIVE");
        StringBuilder sb = new StringBuilder();
        UserProfile prof = userProfileRepository.findByUserId(userId).orElse(null);
        KnowledgeRetriever.RetrievalResult kr = knowledgeRetriever.retrieve(
                prof == null ? null : prof.getGoal(),
                prof == null ? null : prof.getInjuries(),
                null);
        if (activeOpt.isEmpty()) {
            sb.append("当前没有生效的计划。生成后会给出每一条编排依据。\n");
        } else {
            Plan active = activeOpt.get();
            PlanVO vo = PlanSupport.toVO(active,
                    planDetailRepository.findByPlanIdOrderByDayOfWeekAscSeqAsc(active.getId()),
                    exerciseRepository, objectMapper);
            sb.append("当前计划 v").append(vo.getVersion()).append("（来源：")
                    .append(sourceName(vo.getSourceLink())).append("）的依据：\n");
            sb.append("1) 处方约束（检索自知识库，命中 ").append(kr.getHitCount()).append(" 条）：\n");
            if (kr.getConstraintText() != null && !kr.getConstraintText().isBlank()) {
                for (String line : kr.getConstraintText().split("\n")) {
                    if (!line.isBlank()) sb.append("   ").append(line).append('\n');
                }
            } else {
                sb.append("   （未命中条件相关条目，按通用规则编排）\n");
            }
            sb.append("2) 结构：每周 ").append(vo.getWeeklyDays()).append(" 练、每次 ")
                    .append(vo.getSessionMinutes()).append(" 分钟，周有氧 ")
                    .append(vo.getWeeklyCardioMin() == null ? 0 : vo.getWeeklyCardioMin()).append(" 分钟。\n");
            if (vo.getValidationReport() != null) {
                sb.append("3) 校验：得分 ").append(vo.getValidationReport().getScore());
                if (vo.getValidationReport().getChecks() != null
                        && !vo.getValidationReport().getChecks().isEmpty()) {
                    sb.append("，待改进项 ");
                    for (ValidationReport.Check c : vo.getValidationReport().getChecks()) {
                        sb.append('[').append(c.getCode()).append(' ').append(c.getLevel()).append("] ");
                    }
                    sb.append('\n');
                }
            }
            if (vo.getRiskNotes() != null) {
                for (String r : vo.getRiskNotes()) {
                    sb.append("4) 风险提示：").append(r).append('\n');
                    break;
                }
            }
        }
        sb.append("如需修改，直接说「把X换成Y」「去掉X」或「强度降一点」。");
        return RevisePlanResponse.builder().action("QUERY").reply(sb.toString()).build();
    }

    private RevisePlanResponse doChat() {
        return RevisePlanResponse.builder().action("CHAT")
                .reply("这属于普通对话，请到 AI 伙伴页继续；这里支持：生成计划、局部修订（把X换成Y/去掉X/强度降一点）、计划依据解释。")
                .build();
    }

    // ============================================================
    // 修订操作实现（返回 Diff 列表）
    // ============================================================

    /**
     * 定位目标动作：精确等值 > 动作名包含目标名（短名指代长名，如"深蹲"→"徒手深蹲"）。
     * 不做反向包含（"跪姿俯卧撑"包含"俯卧撑"会误伤同名基础动作）；
     * 两轮都未命中且没给名字/日期时，兜底第一个力量动作。
     */
    private List<PlanDayItem> locate(PlanVO vo, IntentRouter.RouteResult route) {
        String name = route.exerciseName();
        if (name != null && !name.isBlank()) {
            // 第一轮：精确等值
            List<PlanDayItem> exact = matchItems(vo, route, name,
                    (itemName, target) -> itemName.equals(target));
            if (!exact.isEmpty()) return exact;
            // 第二轮：动作名包含目标名（目标名是动作名的子串）
            List<PlanDayItem> fuzzy = matchItems(vo, route, name,
                    (itemName, target) -> itemName.contains(target));
            if (!fuzzy.isEmpty()) return fuzzy;
            return new ArrayList<>();
        }
        if (route.dayOfWeek() != null) {
            // 只给了日期：返回该训练日全部动作
            return matchItems(vo, route, null, (itemName, target) -> true);
        }
        // 兜底：第一个力量动作
        List<PlanDayItem> hits = new ArrayList<>();
        outer:
        for (PlanDayVO day : vo.getDays()) {
            if (day.getItems() == null) continue;
            for (PlanItemVO item : day.getItems()) {
                if ("力量".equals(item.getCategory()) || item.getMuscleGroup() != null) {
                    hits.add(new PlanDayItem(day, item));
                    break outer;
                }
            }
        }
        return hits;
    }

    private interface NameMatcher {
        boolean matches(String itemName, String target);
    }

    private List<PlanDayItem> matchItems(PlanVO vo, IntentRouter.RouteResult route, String target,
                                         NameMatcher matcher) {
        List<PlanDayItem> hits = new ArrayList<>();
        for (PlanDayVO day : vo.getDays()) {
            if (day.getItems() == null || Boolean.TRUE.equals(day.getRestDay())) continue;
            if (route.dayOfWeek() != null && route.dayOfWeek() != day.getDayOfWeek()) continue;
            for (PlanItemVO item : day.getItems()) {
                if (item.getExerciseName() == null) continue;
                if (target == null || matcher.matches(item.getExerciseName(), target)) {
                    hits.add(new PlanDayItem(day, item));
                }
            }
        }
        return hits;
    }

    private record PlanDayItem(PlanDayVO day, PlanItemVO item) {}

    /** REPLACE：候选集内找替代（名称匹配 > 同肌群无禁忌最低难度），找不到退化为删除。 */
    private List<RevisePlanResponse.DiffItem> applyReplace(PlanVO vo, IntentRouter.RouteResult route,
                                                           UserProfile prof) {
        List<PlanDayItem> targets = locate(vo, route);
        List<RevisePlanResponse.DiffItem> diff = new ArrayList<>();
        if (targets.isEmpty()) return diff;

        int level = PlanSupport.experienceToLevel(prof == null ? null : prof.getExperience());
        Set<String> injuries = PlanSupport.parseCsv(prof == null ? null : prof.getInjuries(), null);
        List<Exercise> candidates = exerciseRepository.findCandidates(level, equipmentList(prof));
        if (candidates == null || candidates.isEmpty()) {
            candidates = exerciseRepository.findAll();
        }

        for (PlanDayItem t : targets) {
            Exercise replacement = pickReplacement(candidates, route.replaceHint(),
                    t.item().getMuscleGroup(), t.item().getExerciseId(), injuries);
            if (replacement == null) {
                // 无替代：删除
                t.day().getItems().remove(t.item());
                normalizeSeq(t.day());
                if (t.day().getItems().isEmpty()) markRest(t.day());
                PlanSupport.reSummarize(t.day(), weightOf(prof), Map.of());
                diff.add(RevisePlanResponse.DiffItem.builder()
                        .type("REMOVED").dayLabel(t.day().getDayLabel())
                        .exerciseName(t.item().getExerciseName())
                        .detail(t.item().getExerciseName() + " 已移除（无合适替代动作）").build());
                continue;
            }
            String oldName = t.item().getExerciseName();
            t.item().setExerciseId(replacement.getId());
            t.item().setExerciseName(replacement.getName());
            t.item().setMuscleGroup(replacement.getMuscleGroup());
            t.item().setCategory(replacement.getCategory());
            t.item().setEstCalories(PlanSupport.estimateCalories(
                    replacement, t.item().getSets() == null ? 3 : t.item().getSets(), t.item().getReps(), weightOf(prof)));
            PlanSupport.reSummarize(t.day(), weightOf(prof), Map.of(replacement.getId(), replacement));
            diff.add(RevisePlanResponse.DiffItem.builder()
                    .type("MODIFIED").dayLabel(t.day().getDayLabel())
                    .exerciseName(replacement.getName())
                    .detail(oldName + " → " + replacement.getName()
                            + (route.replaceHint() != null ? "（按你的要求）" : "（同肌群替代）")).build());
        }
        return diff;
    }

    /** ADJUST_RPE：目标项或全计划 RPE ±1（clamp 4–9）。 */
    private List<RevisePlanResponse.DiffItem> applyAdjustRpe(PlanVO vo, IntentRouter.RouteResult route) {
        List<PlanDayItem> targets = locate(vo, route);
        List<RevisePlanResponse.DiffItem> diff = new ArrayList<>();
        boolean raise = route.note() != null && route.note().contains("提升");
        int delta = raise ? 1 : -1;
        List<PlanDayItem> scope = targets.isEmpty()
                ? allStrengthItems(vo) : targets;
        for (PlanDayItem t : scope) {
            int oldRpe = t.item().getRpe() == null ? 7 : t.item().getRpe();
            int newRpe = PlanSupport.clamp(oldRpe + delta, 4, 9);
            if (newRpe != oldRpe) {
                t.item().setRpe(newRpe);
                diff.add(RevisePlanResponse.DiffItem.builder()
                        .type("MODIFIED").dayLabel(t.day().getDayLabel())
                        .exerciseName(t.item().getExerciseName())
                        .detail(String.format("%s 强度 RPE %d→%d", t.item().getExerciseName(), oldRpe, newRpe))
                        .build());
            }
        }
        return diff;
    }

    /** REMOVE：移除目标动作。 */
    private List<RevisePlanResponse.DiffItem> applyRemove(PlanVO vo, IntentRouter.RouteResult route,
                                                          UserProfile prof) {
        List<PlanDayItem> targets = locate(vo, route);
        List<RevisePlanResponse.DiffItem> diff = new ArrayList<>();
        for (PlanDayItem t : targets) {
            t.day().getItems().remove(t.item());
            normalizeSeq(t.day());
            if (t.day().getItems().isEmpty()) markRest(t.day());
            PlanSupport.reSummarize(t.day(), weightOf(prof), Map.of());
            diff.add(RevisePlanResponse.DiffItem.builder()
                    .type("REMOVED").dayLabel(t.day().getDayLabel())
                    .exerciseName(t.item().getExerciseName())
                    .detail(t.item().getExerciseName() + " 已移除").build());
        }
        return diff;
    }

    private List<PlanDayItem> allStrengthItems(PlanVO vo) {
        List<PlanDayItem> all = new ArrayList<>();
        for (PlanDayVO day : vo.getDays()) {
            if (day.getItems() == null || Boolean.TRUE.equals(day.getRestDay())) continue;
            for (PlanItemVO item : day.getItems()) {
                if (item.getMuscleGroup() != null && !"全身".equals(item.getMuscleGroup())) {
                    all.add(new PlanDayItem(day, item));
                }
            }
        }
        return all;
    }

    private Exercise pickReplacement(List<Exercise> candidates, String hint, String muscleGroup,
                                     Long excludeId, Set<String> injuries) {
        Exercise best = null;
        for (Exercise c : candidates) {
            if (excludeId != null && excludeId.equals(c.getId())) continue;
            if (PlanSupport.hitContraindication(c, injuries)) continue;
            if (hint != null && !hint.isBlank() && c.getName() != null && c.getName().contains(hint)) {
                return c; // 名称直接命中，最优
            }
            if (best == null && muscleGroup != null && muscleGroup.equals(c.getMuscleGroup())) {
                best = c;
            }
        }
        if (best != null) return best;
        // 再退：任意非禁忌动作
        for (Exercise c : candidates) {
            if (excludeId != null && excludeId.equals(c.getId())) continue;
            if (!PlanSupport.hitContraindication(c, injuries)) return c;
        }
        return null;
    }

    // ============================================================
    // 落库 / 工具
    // ============================================================

    /** 修订版本落库（复制 Orchestrator ⑬ 段语义，sourceLink=REVISED）。 */
    private Plan persistAsNewVersion(Long userId, Plan active, PlanVO vo, ValidationReport report,
                                     String changeNote) {
        active.setStatus("ARCHIVED");
        planRepository.save(active);
        int version = planRepository.findMaxVersion(userId) + 1;
        Plan plan = Plan.builder()
                .userId(userId)
                .title(vo.getTitle())
                .goal(active.getGoal())
                .weeklyDays(active.getWeeklyDays())
                .sessionMinutes(active.getSessionMinutes())
                .summary(vo.getSummary())
                .weeklyCardioMin(vo.getWeeklyCardioMin())
                .weeklyCalories(vo.getWeeklyCalories())
                .aiGenerated(active.getAiGenerated())
                .status("ACTIVE")
                .version(version)
                .parentId(active.getId())
                .changeNote(truncate(changeNote, 500))
                .validationReport(writeJson(report))
                .knowledgeSnapshot(active.getKnowledgeSnapshot())
                .sourceLink("REVISED")
                .generationMs(0)
                .aiTokens(0)
                .build();
        planRepository.save(plan);
        List<PlanDetail> details = new ArrayList<>();
        for (PlanDayVO day : vo.getDays()) {
            if (day.getItems() == null) continue;
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
        PlanOrchestrator.fillDetailIds(vo, details);
        return plan;
    }

    private PlanValidator.VContext buildContext(PlanVO vo, UserProfile prof) {
        int age = prof == null || prof.getAge() == null ? 30 : prof.getAge();
        return new PlanValidator.VContext(
                age,
                prof == null ? null : prof.getHrRest(),
                PlanSupport.experienceToLevel(prof == null ? null : prof.getExperience()),
                vo.getGoal(),
                vo.getWeeklyDays() == null ? 3 : vo.getWeeklyDays(),
                vo.getSessionMinutes() == null ? 45 : vo.getSessionMinutes(),
                new ArrayList<>(PlanSupport.parseCsv(prof == null ? null : prof.getInjuries(), null)),
                weightOf(prof));
    }

    private Map<Long, Exercise> loadCandidatesFor(PlanVO vo) {
        Set<Long> ids = new HashSet<>();
        for (PlanDayVO day : vo.getDays()) {
            if (day.getItems() == null) continue;
            for (PlanItemVO item : day.getItems()) {
                if (item.getExerciseId() != null) ids.add(item.getExerciseId());
            }
        }
        Map<Long, Exercise> map = new HashMap<>();
        for (Exercise e : exerciseRepository.findAllById(ids)) map.put(e.getId(), e);
        // 补充候选集（V5 校验需要替代动作的禁忌信息已在 map 内；候选不强制）
        return map;
    }

    /** 修订前快照：dayLabel|exerciseName|sets → 供 Diff 与旧周组数基线。 */
    private Map<String, Integer> snapshot(PlanVO vo) {
        Map<String, Integer> snap = new LinkedHashMap<>();
        for (PlanDayVO day : vo.getDays()) {
            if (day.getItems() == null) continue;
            for (PlanItemVO item : day.getItems()) {
                snap.put(day.getDayLabel() + "|" + item.getExerciseName(),
                        item.getSets() == null ? 0 : item.getSets());
            }
        }
        return snap;
    }

    /** 修订前 name→muscle 映射（在 vo 被修改前采集）。 */
    private Map<String, String> nameToMuscle(PlanVO vo) {
        Map<String, String> m = new HashMap<>();
        for (PlanDayVO day : vo.getDays()) {
            if (day.getItems() == null) continue;
            for (PlanItemVO item : day.getItems()) {
                if (item.getMuscleGroup() != null) {
                    m.put(item.getExerciseName(), item.getMuscleGroup());
                }
            }
        }
        return m;
    }

    /** 用修订前快照重算旧周组数（V3 环比基线）。 */
    private Map<String, Integer> oldWeeklySets(Map<String, Integer> oldSnapshot,
                                               Map<String, String> oldNameToMuscle) {
        Map<String, Integer> sets = new HashMap<>();
        for (Map.Entry<String, Integer> e : oldSnapshot.entrySet()) {
            String name = e.getKey().substring(e.getKey().indexOf('|') + 1);
            String muscle = oldNameToMuscle.get(name);
            if (muscle == null || "全身".equals(muscle)) continue;
            sets.merge(muscle, e.getValue(), Integer::sum);
        }
        return sets;
    }

    private List<String> equipmentList(UserProfile prof) {
        Set<String> ok = PlanSupport.parseCsv(prof == null ? null : prof.getEquipments(), null);
        if (ok == null) ok = new HashSet<>();
        ok.retainAll(PlanSupport.EQUIPMENT_WHITELIST);
        if (ok.isEmpty()) ok.add("徒手");
        return new ArrayList<>(ok);
    }

    private double weightOf(UserProfile prof) {
        return prof == null || prof.getWeight() == null ? 65.0 : prof.getWeight();
    }

    private void normalizeSeq(PlanDayVO day) {
        if (day.getItems() == null) return;
        int seq = 1;
        for (PlanItemVO item : day.getItems()) {
            item.setSeq(seq++);
        }
    }

    private void markRest(PlanDayVO day) {
        day.setRestDay(true);
        day.setFocus("休息");
        day.setEstMinutes(0);
        day.setEstCalories(0.0);
    }

    private String writeJson(ValidationReport report) {
        if (report == null) return null;
        try {
            return objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            return null;
        }
    }

    private int countLevel(ValidationReport report, String level) {
        int n = 0;
        if (report.getChecks() != null) {
            for (ValidationReport.Check c : report.getChecks()) {
                if (level.equalsIgnoreCase(c.getLevel())) n++;
            }
        }
        return n;
    }

    private static String sourceName(String link) {
        if (link == null) return "大模型";
        return switch (link) {
            case "TEMPLATE" -> "规则模板";
            case "LLM_REPAIRED" -> "大模型+自动修复";
            case "REVISED" -> "局部修订";
            default -> "大模型";
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
