package com.fitlife.service;

import com.fasterxml.jackson.databind.JsonNode;
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
 * 训练计划生成服务。
 *
 * <p>采用“检索增强生成”链路：
 * <ol>
 *   <li>规则引擎按难度、器械、禁忌症从动作库中筛出候选动作（保证不出现医学风险动作）；</li>
 *   <li>把用户画像 + 候选动作清单组装成 Prompt，交由大模型做训练分化编排；</li>
 *   <li>对模型返回的 JSON 做严格校验，动作必须存在于候选集中，否则拒收；</li>
 *   <li>校验失败或模型不可用时，降级为规则模板计划，保证功能可用。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanService {

    private final ExerciseRepository exerciseRepository;
    private final PlanRepository planRepository;
    private final PlanDetailRepository planDetailRepository;
    private final UserProfileRepository profileRepository;
    private final AiService aiService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String[] DAY_LABELS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /** 器械白名单：防止前端传入未知器械导致候选集为空 */
    private static final Set<String> EQUIPMENT_WHITELIST =
            new LinkedHashSet<>(Arrays.asList("徒手", "哑铃", "杠铃", "器械", "弹力带", "壶铃"));

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
     * 生成训练计划：规则筛选 → 大模型编排 → 校验 → 落库
     */
    @Transactional
    public ApiResponse generatePlan(Long userId, GeneratePlanRequest req) {
        // 未强制重建时，复用已有计划
        if (req.getForce() == null || !req.getForce()) {
            Optional<Plan> exist = planRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, "ACTIVE");
            if (exist.isPresent()) {
                return ApiResponse.success(toVO(exist.get()));
            }
        }

        UserProfile profile = profileRepository.findByUserId(userId).orElse(null);

        // ---------- 1. 补齐参数 ----------
        String goal = firstNonBlank(req.getGoal(), profile != null ? profile.getGoal() : null, "保持健康");
        String experience = firstNonBlank(req.getExperience(),
                profile != null ? profile.getExperience() : null, "初级");
        int weeklyDays = clamp(req.getWeeklyDays() != null ? req.getWeeklyDays()
                : (profile != null && profile.getWeeklyDays() != null ? profile.getWeeklyDays() : 3), 1, 7);
        int sessionMinutes = clamp(req.getSessionMinutes() != null ? req.getSessionMinutes()
                : (profile != null && profile.getSessionMinutes() != null ? profile.getSessionMinutes() : 45), 15, 120);
        Set<String> equipments = parseCsv(firstNonBlank(req.getEquipments(),
                profile != null ? profile.getEquipments() : null, "徒手"), EQUIPMENT_WHITELIST);
        Set<String> injuries = parseCsv(firstNonBlank(req.getInjuries(),
                profile != null ? profile.getInjuries() : null, ""), null);
        int level = experienceToLevel(experience);

        double weightKg = profile != null && profile.getWeight() != null ? profile.getWeight() : 65.0;

        // ---------- 2. 规则引擎筛选候选动作 ----------
        List<Exercise> rawCandidates = exerciseRepository.findCandidates(level, equipments);
        List<Exercise> candidates = rawCandidates.stream()
                .filter(e -> !hitContraindication(e, injuries))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return ApiResponse.error("当前器械与经验条件下没有可用动作，请放宽器械条件");
        }

        // ---------- 3. 大模型编排 ----------
        PlanVO vo = null;
        String note = null;
        try {
            String prompt = buildPlanPrompt(goal, experience, weeklyDays, sessionMinutes,
                    equipments, injuries, weightKg, candidates);
            String raw = aiService.callAi(prompt, 0.4, 3000);
            vo = parseAiPlan(raw, candidates, weeklyDays, sessionMinutes, weightKg);
            if (vo != null) {
                note = "由大模型基于动作库编排生成";
            }
        } catch (Exception e) {
            log.warn("AI 生成计划失败，降级为规则模板: {}", e.getMessage());
            note = "AI 服务暂不可用，已使用内置规则模板生成：" + e.getMessage();
        }

        // ---------- 4. 兜底：规则模板 ----------
        boolean aiGenerated = vo != null;
        if (vo == null) {
            vo = buildTemplatePlan(goal, level, weeklyDays, sessionMinutes, candidates, weightKg);
            if (note == null) {
                note = "已使用内置规则模板生成";
            }
        }
        vo.setAiGenerated(aiGenerated);
        vo.setNote(note);
        vo.setGoal(goal);

        // ---------- 5. 落库 ----------
        // 旧计划归档，保留历史
        planRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, "ACTIVE")
                .ifPresent(old -> {
                    old.setStatus("ARCHIVED");
                    planRepository.save(old);
                });

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

        // 直接返回本次生成的 VO，保留 note（AI 或规则模板）、完整动作要领等元信息
        vo.setId(plan.getId());
        vo.setStatus(plan.getStatus());
        vo.setCreatedAt(plan.getCreatedAt());
        return ApiResponse.success(vo);
    }

    // ============================================================
    // Prompt 构造
    // ============================================================

    private String buildPlanPrompt(String goal, String experience, int weeklyDays, int sessionMinutes,
                                   Set<String> equipments, Set<String> injuries,
                                   double weightKg, List<Exercise> candidates) throws Exception {

        // 候选动作精简投影，控制 token 消耗
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
        String candidateJson = objectMapper.writeValueAsString(brief);

        String injuryText = injuries.isEmpty() ? "无" : String.join("、", injuries);

        return "你是一名专业体能教练，需要为健身系统生成一周训练计划。\n\n"
                + "【用户信息】\n"
                + "- 健身目标：" + goal + "\n"
                + "- 运动经验：" + experience + "\n"
                + "- 每周训练天数：" + weeklyDays + " 天\n"
                + "- 单次训练时长：" + sessionMinutes + " 分钟\n"
                + "- 可用器械：" + String.join("、", equipments) + "\n"
                + "- 伤病史/禁忌部位：" + injuryText + "\n"
                + "- 体重：" + weightKg + " kg\n\n"
                + "【可选动作库】以下是系统中已审核的动作，你只能从中选择，禁止编造动作或使用列表外的动作名：\n"
                + candidateJson + "\n\n"
                + "【编排要求】\n"
                + "1. 只输出 JSON，不要任何解释文字、不要 markdown 代码块标记。\n"
                + "2. 训练天数必须正好 " + weeklyDays + " 天，其余为休息日；休息日 items 为空数组。\n"
                + "3. 每次训练包含 4-6 个动作：先做复合动作（力量），再补充孤立或核心动作。\n"
                + "4. 如果是减脂目标，每个训练日末尾安排 1 个有氧动作；每周有氧总时长不低于 150 分钟。\n"
                + "5. 如果是增肌目标，同一肌群每周至少安排 2 次刺激，主要肌群不要连续两天训练。\n"
                + "6. 避免同一肌群连续两天高强度训练；初级用户每个动作 3 组，高级用户可 4-5 组。\n"
                + "7. 组间休息：力量动作 60-120 秒，核心/柔韧 30-60 秒，有氧按分钟安排。\n"
                + "8. 所有 exerciseId 必须严格取自上面动作库的 id 字段。\n"
                + "9. summary 用 2-3 句中文说明计划思路与注意事项。\n\n"
                + "【输出 JSON 结构】\n"
                + "{\n"
                + "  \"title\": \"计划标题(中文，20字内)\",\n"
                + "  \"summary\": \"计划思路说明\",\n"
                + "  \"weeklyCardioMin\": 150,\n"
                + "  \"days\": [\n"
                + "    {\"dayOfWeek\": 1, \"focus\": \"胸 + 三头\", \"items\": [\n"
                + "      {\"exerciseId\": 6, \"sets\": 4, \"reps\": \"8-12\", \"restSec\": 90, \"rpe\": 7, \"seq\": 1}\n"
                + "    ]},\n"
                + "    {\"dayOfWeek\": 2, \"focus\": \"休息\", \"items\": []}\n"
                + "  ]\n"
                + "}";
    }

    // ============================================================
    // 解析与校验
    // ============================================================

    /**
     * 解析模型返回的 JSON，并做严格校验：
     * 动作 id 必须存在于候选集，组数/次数/休息需在合理区间。
     * 校验不通过返回 null，由上层降级。
     */
    private PlanVO parseAiPlan(String raw, List<Exercise> candidates, int weeklyDays,
                               int sessionMinutes, double weightKg) {
        try {
            String json = extractJson(raw);
            if (json == null) {
                log.warn("AI 返回内容中未找到 JSON");
                return null;
            }
            JsonNode root = objectMapper.readTree(json);

            Map<Long, Exercise> byId = candidates.stream()
                    .collect(Collectors.toMap(Exercise::getId, e -> e));

            Map<Integer, PlanDayVO> dayMap = new LinkedHashMap<>();
            JsonNode daysNode = root.path("days");
            if (!daysNode.isArray() || daysNode.size() == 0) {
                return null;
            }

            for (JsonNode dn : daysNode) {
                int dow = dn.path("dayOfWeek").asInt(0);
                if (dow < 1 || dow > 7) {
                    continue;
                }
                String focus = dn.path("focus").asText("");
                List<PlanItemVO> items = new ArrayList<>();
                JsonNode itemsNode = dn.path("items");
                int seq = 1;
                if (itemsNode.isArray()) {
                    for (JsonNode it : itemsNode) {
                        long exId = it.path("exerciseId").asLong(0);
                        Exercise ex = byId.get(exId);
                        if (ex == null) {
                            // 模型幻觉出列表外动作，直接丢弃该条
                            log.warn("丢弃非法动作 id={}", exId);
                            continue;
                        }
                        int sets = clamp(it.path("sets").asInt(ex.getDefaultSets() == null ? 3 : ex.getDefaultSets()), 1, 6);
                        String reps = it.path("reps").asText(
                                ex.getDefaultReps() == null ? "10-12" : ex.getDefaultReps());
                        int rest = clamp(it.path("restSec").asInt(60), 15, 300);
                        int rpe = clamp(it.path("rpe").asInt(6), 1, 10);

                        items.add(PlanItemVO.builder()
                                .exerciseId(ex.getId())
                                .exerciseName(ex.getName())
                                .muscleGroup(ex.getMuscleGroup())
                                .category(ex.getCategory())
                                .sets(sets)
                                .reps(reps)
                                .restSec(rest)
                                .rpe(rpe)
                                .seq(it.path("seq").asInt(seq))
                                .description(ex.getDescription())
                                .estCalories(estimateCalories(ex, sets, reps, weightKg))
                                .build());
                        seq++;
                    }
                }
                if (items.isEmpty()) {
                    dayMap.put(dow, PlanDayVO.builder()
                            .dayOfWeek(dow).dayLabel(DAY_LABELS[dow - 1])
                            .focus(focus.isEmpty() ? "休息" : focus)
                            .restDay(true).estMinutes(0).estCalories(0.0)
                            .items(new ArrayList<>()).build());
                } else {
                    dayMap.put(dow, summarizeDay(dow, focus, items));
                }
            }

            // 训练天数必须与要求一致（容忍 ±1，避免过度严格导致频繁降级）
            long trainDays = dayMap.values().stream().filter(d -> !d.getRestDay()).count();
            if (Math.abs(trainDays - weeklyDays) > 1) {
                log.warn("AI 计划训练天数不符：要求 {}，实际 {}", weeklyDays, trainDays);
                return null;
            }

            // 补齐缺失的天，保证返回 7 天
            for (int d = 1; d <= 7; d++) {
                dayMap.computeIfAbsent(d, k -> PlanDayVO.builder()
                        .dayOfWeek(k).dayLabel(DAY_LABELS[k - 1])
                        .focus("休息").restDay(true).estMinutes(0).estCalories(0.0)
                        .items(new ArrayList<>()).build());
            }

            List<PlanDayVO> days = dayMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .collect(Collectors.toList());

            double weeklyCal = days.stream()
                    .mapToDouble(d -> d.getEstCalories() == null ? 0 : d.getEstCalories()).sum();

            return PlanVO.builder()
                    .title(root.path("title").asText("我的训练计划"))
                    .summary(root.path("summary").asText(""))
                    .weeklyCardioMin(root.path("weeklyCardioMin").asInt(150))
                    .weeklyCalories(Math.round(weeklyCal * 10) / 10.0)
                    .weeklyDays(weeklyDays)
                    .sessionMinutes(sessionMinutes)
                    .days(days)
                    .build();

        } catch (Exception e) {
            log.warn("解析 AI 计划 JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    /** 从可能带 markdown 包裹的文本中提取 JSON 主体 */
    private String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return s.substring(start, end + 1);
    }

    // ============================================================
    // 规则模板兜底
    // ============================================================

    /**
     * 兜底模板：不依赖大模型，按肌群分化规则直接编排。
     * 保证即使 API 不可用，用户仍能拿到一份结构完整、符合安全约束的计划。
     */
    private PlanVO buildTemplatePlan(String goal, int level, int weeklyDays, int sessionMinutes,
                                     List<Exercise> candidates, double weightKg) {

        Map<String, List<Exercise>> byMuscle = candidates.stream()
                .filter(e -> "力量".equals(e.getCategory()) || "核心".equals(e.getCategory()))
                .collect(Collectors.groupingBy(Exercise::getMuscleGroup,
                        LinkedHashMap::new, Collectors.toList()));
        List<Exercise> cardio = candidates.stream()
                .filter(e -> "有氧".equals(e.getCategory()) || "HIIT".equals(e.getCategory()))
                .collect(Collectors.toList());
        List<Exercise> core = candidates.stream()
                .filter(e -> "核心".equals(e.getCategory()))
                .collect(Collectors.toList());

        // 训练分化模板
        List<List<String>> split;
        List<String> splitFocus;
        if (weeklyDays <= 2) {
            split = List.of(List.of("全身"), List.of("全身"));
            splitFocus = List.of("全身力量", "全身力量 + 核心");
        } else if (weeklyDays == 3) {
            split = List.of(List.of("胸", "肩", "手臂"), List.of("背", "腿"), List.of("腿", "臀", "核心"));
            splitFocus = List.of("推力日：胸 + 肩 + 三头", "拉力日：背 + 腿", "下肢与核心");
        } else if (weeklyDays == 4) {
            split = List.of(List.of("胸", "手臂"), List.of("背", "核心"),
                    List.of("腿", "臀"), List.of("肩", "全身"));
            splitFocus = List.of("胸 + 三头", "背 + 核心", "腿 + 臀", "肩 + 全身");
        } else if (weeklyDays == 5) {
            split = List.of(List.of("胸"), List.of("背"), List.of("腿", "臀"),
                    List.of("肩", "手臂"), List.of("核心", "全身"));
            splitFocus = List.of("胸部专项", "背部专项", "腿臀专项", "肩臂专项", "核心 + 全身");
        } else {
            split = List.of(List.of("胸"), List.of("背"), List.of("腿"),
                    List.of("肩"), List.of("手臂"), List.of("核心", "全身"));
            splitFocus = List.of("胸部", "背部", "腿部", "肩部", "手臂", "核心与全身");
        }

        List<PlanDayVO> days = new ArrayList<>();
        int trainIdx = 0;
        // 均匀分布训练日
        Set<Integer> trainDows = new LinkedHashSet<>();
        if (weeklyDays >= 7) {
            for (int i = 1; i <= 7; i++) trainDows.add(i);
        } else {
            double step = 7.0 / weeklyDays;
            for (int i = 0; i < weeklyDays; i++) {
                trainDows.add((int) Math.round(i * step) + 1);
            }
        }

        boolean isCut = isCutGoal(goal);

        for (int dow = 1; dow <= 7; dow++) {
            if (!trainDows.contains(dow)) {
                days.add(PlanDayVO.builder().dayOfWeek(dow).dayLabel(DAY_LABELS[dow - 1])
                        .focus("休息").restDay(true).estMinutes(0).estCalories(0.0)
                        .items(new ArrayList<>()).build());
                continue;
            }
            int idx = Math.min(trainIdx, split.size() - 1);
            List<String> muscles = split.get(idx);
            String focus = splitFocus.get(idx);
            trainIdx++;

            List<Exercise> picked = new ArrayList<>();
            // 从目标肌群各取动作
            for (String m : muscles) {
                List<Exercise> pool = "全身".equals(m)
                        ? candidates.stream().filter(e -> "全身".equals(e.getMuscleGroup())).collect(Collectors.toList())
                        : byMuscle.getOrDefault(m, Collections.emptyList());
                picked.addAll(pool.stream().limit(2).collect(Collectors.toList()));
            }
            if (picked.size() < 3) {
                picked.addAll(byMuscle.values().stream().flatMap(List::stream)
                        .filter(e -> !picked.contains(e)).limit(3 - picked.size())
                        .collect(Collectors.toList()));
            }
            picked = picked.stream().distinct().limit(sessionMinutes <= 30 ? 4 : 5).collect(Collectors.toList());

            List<PlanItemVO> items = new ArrayList<>();
            int seq = 1;
            for (Exercise e : picked) {
                int sets = level >= 3 ? 4 : 3;
                String reps = e.getDefaultReps() == null ? "10-12" : e.getDefaultReps();
                items.add(PlanItemVO.builder()
                        .exerciseId(e.getId()).exerciseName(e.getName())
                        .muscleGroup(e.getMuscleGroup()).category(e.getCategory())
                        .sets(sets).reps(reps)
                        .restSec("核心".equals(e.getCategory()) ? 45 : 75)
                        .rpe(level >= 3 ? 8 : 6).seq(seq++)
                        .description(e.getDescription())
                        .estCalories(estimateCalories(e, sets, reps, weightKg))
                        .build());
            }
            // 核心补充
            if (!core.isEmpty() && items.size() < 5) {
                Exercise c = core.get(dow % core.size());
                items.add(PlanItemVO.builder()
                        .exerciseId(c.getId()).exerciseName(c.getName())
                        .muscleGroup(c.getMuscleGroup()).category(c.getCategory())
                        .sets(3).reps(c.getDefaultReps() == null ? "15-20" : c.getDefaultReps())
                        .restSec(45).rpe(6).seq(seq++)
                        .description(c.getDescription())
                        .estCalories(estimateCalories(c, 3, c.getDefaultReps(), weightKg))
                        .build());
            }
            // 减脂目标追加有氧
            if (isCut && !cardio.isEmpty()) {
                Exercise c = cardio.get(dow % cardio.size());
                items.add(PlanItemVO.builder()
                        .exerciseId(c.getId()).exerciseName(c.getName())
                        .muscleGroup(c.getMuscleGroup()).category(c.getCategory())
                        .sets(1).reps("20分钟")
                        .restSec(0).rpe(5).seq(seq++)
                        .description(c.getDescription())
                        .estCalories(estimateCalories(c, 1, "20分钟", weightKg))
                        .build());
            }
            days.add(summarizeDay(dow, focus, items));
        }

        double weeklyCal = days.stream()
                .mapToDouble(d -> d.getEstCalories() == null ? 0 : d.getEstCalories()).sum();

        return PlanVO.builder()
                .title(goal + "·" + weeklyDays + "天训练计划")
                .summary("依据你的目标（" + goal + "）、经验水平与可用器械，按肌群分化原则编排。"
                        + "建议每次训练前充分热身 5-10 分钟，训练后拉伸放松，并保证充足睡眠。"
                        + "如有不适请立即停止并咨询专业人士。")
                .weeklyCardioMin(isCut ? 150 : 60)
                .weeklyCalories(Math.round(weeklyCal * 10) / 10.0)
                .weeklyDays(weeklyDays)
                .sessionMinutes(sessionMinutes)
                .days(days)
                .build();
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /** 估算单个动作的热量消耗：MET × 体重 × 时长(h) × 1.05 */
    private double estimateCalories(Exercise e, int sets, String reps, double weightKg) {
        if (e.getMetValue() == null) {
            return 0.0;
        }
        double minutes;
        if ("有氧".equals(e.getCategory())) {
            minutes = parseMinutes(reps, 20);
        } else if ("HIIT".equals(e.getCategory())) {
            minutes = parseMinutes(reps, 10) * Math.max(sets, 1);
        } else {
            // 力量/核心：每组约 45 秒执行 + 组间休息
            double perSet = 0.75 + (sets > 0 ? 1.0 : 0);
            minutes = perSet * Math.max(sets, 1) * 1.0 + sets * 1.2;
        }
        double hours = minutes / 60.0;
        return Math.round(e.getMetValue() * weightKg * hours * 1.05 * 10) / 10.0;
    }

    private int parseMinutes(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)").matcher(text);
        if (m.find()) {
            try {
                return Math.max(1, Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private PlanDayVO summarizeDay(int dow, String focus, List<PlanItemVO> items) {
        double cal = items.stream().mapToDouble(i -> i.getEstCalories() == null ? 0 : i.getEstCalories()).sum();
        int minutes = 0;
        for (PlanItemVO i : items) {
            minutes += "有氧".equals(i.getCategory()) ? parseMinutes(i.getReps(), 20)
                    : ("HIIT".equals(i.getCategory()) ? parseMinutes(i.getReps(), 10) : 8);
        }
        return PlanDayVO.builder()
                .dayOfWeek(dow).dayLabel(DAY_LABELS[dow - 1])
                .focus(focus)
                .restDay(false)
                .estMinutes(minutes)
                .estCalories(Math.round(cal * 10) / 10.0)
                .items(items)
                .build();
    }

    private boolean hitContraindication(Exercise e, Set<String> injuries) {
        if (injuries.isEmpty() || e.getContraindication() == null || e.getContraindication().isBlank()) {
            return false;
        }
        String c = e.getContraindication();
        for (String inj : injuries) {
            if (!inj.isBlank() && c.contains(inj)) {
                return true;
            }
        }
        return false;
    }

    /** 目标判定：兼容中文（减脂/塑形）与前端枚举（fat_loss） */
    private boolean isCutGoal(String goal) {
        if (goal == null) {
            return false;
        }
        String g = goal.toLowerCase();
        return g.contains("减脂") || g.contains("塑形")
                || g.contains("fat") || g.contains("loss") || g.contains("cut");
    }

    private int experienceToLevel(String experience) {
        if (experience == null) {
            return 1;
        }
        if (experience.contains("高") || experience.contains("进阶")) {
            return 3;
        }
        if (experience.contains("中")) {
            return 2;
        }
        return 1;
    }

    private Set<String> parseCsv(String csv, Set<String> whitelist) {
        Set<String> out = new LinkedHashSet<>();
        if (csv != null) {
            for (String s : csv.split("[,，、;；\\s]+")) {
                String t = s.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (whitelist == null || whitelist.contains(t)) {
                    out.add(t);
                }
            }
        }
        if (whitelist != null && out.isEmpty()) {
            out.add("徒手");
        }
        return out;
    }

    private String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private int clamp(Integer v, int min, int max) {
        int x = v == null ? min : v;
        return Math.max(min, Math.min(max, x));
    }

    /** 实体转 VO */
    private PlanVO toVO(Plan plan) {
        List<PlanDetail> details = planDetailRepository
                .findByPlanIdOrderByDayOfWeekAscSeqAsc(plan.getId());

        // 动作要领、类别不在 plan_details 冗余存储，统一回查动作库（唯一数据源）
        Set<Long> exerciseIds = details.stream()
                .map(PlanDetail::getExerciseId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Exercise> exerciseMap = exerciseIds.isEmpty()
                ? Collections.emptyMap()
                : exerciseRepository.findAllById(exerciseIds).stream()
                    .collect(Collectors.toMap(Exercise::getId, e -> e, (a, b) -> a));

        Map<Integer, List<PlanDetail>> grouped = details.stream()
                .collect(Collectors.groupingBy(PlanDetail::getDayOfWeek, TreeMap::new, Collectors.toList()));

        List<PlanDayVO> days = new ArrayList<>();
        for (int dow = 1; dow <= 7; dow++) {
            List<PlanDetail> ds = grouped.get(dow);
            if (ds == null || ds.isEmpty()) {
                days.add(PlanDayVO.builder().dayOfWeek(dow).dayLabel(DAY_LABELS[dow - 1])
                        .focus("休息").restDay(true).estMinutes(0).estCalories(0.0)
                        .items(new ArrayList<>()).build());
                continue;
            }
            List<PlanItemVO> items = ds.stream().map(d -> {
                Exercise ex = d.getExerciseId() == null ? null : exerciseMap.get(d.getExerciseId());
                return PlanItemVO.builder()
                    .exerciseId(d.getExerciseId())
                    .exerciseName(d.getExerciseName())
                    .muscleGroup(d.getMuscleGroup())
                    .category(ex != null ? ex.getCategory() : null)
                    .description(ex != null ? ex.getDescription() : null)
                    .sets(d.getSets())
                    .reps(d.getReps())
                    .restSec(d.getRestSec())
                    .rpe(d.getRpe())
                    .seq(d.getSeq())
                    .estCalories(d.getEstCalories())
                    .build();
            }).collect(Collectors.toList());
            days.add(summarizeDay(dow, ds.get(0).getDayFocus(), items));
        }

        return PlanVO.builder()
                .id(plan.getId())
                .title(plan.getTitle())
                .goal(plan.getGoal())
                .weeklyDays(plan.getWeeklyDays())
                .sessionMinutes(plan.getSessionMinutes())
                .summary(plan.getSummary())
                .weeklyCardioMin(plan.getWeeklyCardioMin())
                .weeklyCalories(plan.getWeeklyCalories())
                .aiGenerated(plan.getAiGenerated())
                .status(plan.getStatus())
                .createdAt(plan.getCreatedAt())
                .days(days)
                .build();
    }
}
