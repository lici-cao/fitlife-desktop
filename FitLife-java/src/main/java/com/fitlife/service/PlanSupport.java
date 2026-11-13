package com.fitlife.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.dto.PlanDayVO;
import com.fitlife.dto.PlanItemVO;
import com.fitlife.dto.PlanVO;
import com.fitlife.dto.ValidationReport;
import com.fitlife.entity.Exercise;
import com.fitlife.entity.Plan;
import com.fitlife.entity.PlanDetail;
import com.fitlife.repository.ExerciseRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 计划编排的纯函数工具集（P2 从 PlanService 抽出）。
 *
 * <p>PlanService（旧端点兼容路径）与 PlanOrchestrator（新主链路）共用同一套
 * 解析 / 模板 / 估算逻辑，避免两份实现漂移。全部静态、无依赖注入。
 */
public final class PlanSupport {

    private PlanSupport() {
    }

    public static final String[] DAY_LABELS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    public static final Set<String> EQUIPMENT_WHITELIST =
            Collections.unmodifiableSet(new LinkedHashSet<>(List.of("徒手", "哑铃", "杠铃", "器械", "弹力带", "壶铃")));

    /**
     * 解析模型返回的 JSON，并做严格校验：
     * 动作 id 必须存在于候选集，组数/次数/休息需在合理区间。
     * 校验不通过返回 null，由上层降级。
     */
    public static PlanVO parseAiPlan(ObjectMapper objectMapper, String raw, List<Exercise> candidates,
                                     int weeklyDays, int sessionMinutes, double weightKg) {
        try {
            String json = extractJson(raw);
            if (json == null) {
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
                            continue; // 模型幻觉出列表外动作，丢弃该条
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

            long trainDays = dayMap.values().stream().filter(d -> !d.getRestDay()).count();
            if (Math.abs(trainDays - weeklyDays) > 1) {
                return null;
            }

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
            return null;
        }
    }

    /** 从可能带 markdown 包裹的文本中提取 JSON 主体 */
    public static String extractJson(String raw) {
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

    /**
     * 兜底模板：不依赖大模型，按肌群分化规则直接编排。
     * 保证即使 API 不可用，用户仍能拿到一份结构完整、符合安全约束的计划。
     */
    public static PlanVO buildTemplatePlan(String goal, int level, int weeklyDays, int sessionMinutes,
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
        Set<Integer> trainDows = new LinkedHashSet<>();
        if (weeklyDays >= 7) {
            for (int i = 1; i <= 7; i++) {
                trainDows.add(i);
            }
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
            for (String m : muscles) {
                List<Exercise> pool = "全身".equals(m)
                        ? candidates.stream().filter(e -> "全身".equals(e.getMuscleGroup())).collect(Collectors.toList())
                        : byMuscle.getOrDefault(m, Collections.emptyList());
                picked.addAll(pool.stream().limit(2).collect(Collectors.toList()));
            }
            if (picked.size() < 3) {
                List<Exercise> base = picked;
                List<Exercise> extra = byMuscle.values().stream().flatMap(List::stream)
                        .filter(e -> !base.contains(e)).limit(3 - base.size())
                        .collect(Collectors.toList());
                picked.addAll(extra);
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

    /** 估算单个动作的热量消耗：MET × 体重 × 时长(h) × 1.05 */
    public static double estimateCalories(Exercise e, int sets, String reps, double weightKg) {
        if (e.getMetValue() == null) {
            return 0.0;
        }
        double minutes;
        if ("有氧".equals(e.getCategory())) {
            minutes = parseMinutes(reps, 20);
        } else if ("HIIT".equals(e.getCategory())) {
            minutes = parseMinutes(reps, 10) * Math.max(sets, 1);
        } else {
            double perSet = 0.75 + (sets > 0 ? 1.0 : 0);
            minutes = perSet * Math.max(sets, 1) * 1.0 + sets * 1.2;
        }
        double hours = minutes / 60.0;
        return Math.round(e.getMetValue() * weightKg * hours * 1.05 * 10) / 10.0;
    }

    public static int parseMinutes(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        Matcher m = Pattern.compile("(\\d+)").matcher(text);
        if (m.find()) {
            try {
                return Math.max(1, Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static PlanDayVO summarizeDay(int dow, String focus, List<PlanItemVO> items) {
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

    /** 重算一天的汇总字段（修复器改动 items 后调用） */
    public static void reSummarize(PlanDayVO day, double weightKg, Map<Long, Exercise> candidateById) {
        if (day == null || day.getItems() == null) {
            return;
        }
        for (PlanItemVO it : day.getItems()) {
            Exercise ex = it.getExerciseId() == null ? null : candidateById.get(it.getExerciseId());
            if (ex != null) {
                it.setEstCalories(estimateCalories(ex, it.getSets() == null ? 3 : it.getSets(), it.getReps(), weightKg));
            }
        }
        PlanDayVO fresh = summarizeDay(day.getDayOfWeek(), day.getFocus(), day.getItems());
        day.setEstMinutes(fresh.getEstMinutes());
        day.setEstCalories(fresh.getEstCalories());
    }

    /** 重算周汇总（有氧分钟 / 周热量） */
    public static void reSummarizeWeek(PlanVO vo) {
        if (vo == null || vo.getDays() == null) {
            return;
        }
        double weeklyCal = vo.getDays().stream()
                .mapToDouble(d -> d.getEstCalories() == null ? 0 : d.getEstCalories()).sum();
        vo.setWeeklyCalories(Math.round(weeklyCal * 10) / 10.0);
        int cardioMin = 0;
        for (PlanDayVO d : vo.getDays()) {
            if (Boolean.TRUE.equals(d.getRestDay()) || d.getItems() == null) {
                continue;
            }
            for (PlanItemVO it : d.getItems()) {
                if ("有氧".equals(it.getCategory())) {
                    cardioMin += parseMinutes(it.getReps(), 20);
                } else if ("HIIT".equals(it.getCategory())) {
                    cardioMin += parseMinutes(it.getReps(), 10) * Math.max(1, it.getSets() == null ? 1 : it.getSets());
                }
            }
        }
        if (cardioMin > 0) {
            vo.setWeeklyCardioMin(cardioMin);
        }
    }

    public static boolean hitContraindication(Exercise e, Set<String> injuries) {
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
    public static boolean isCutGoal(String goal) {
        if (goal == null) {
            return false;
        }
        String g = goal.toLowerCase();
        return g.contains("减脂") || g.contains("塑形")
                || g.contains("fat") || g.contains("loss") || g.contains("cut");
    }

    public static int experienceToLevel(String experience) {
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

    public static Set<String> parseCsv(String csv, Set<String> whitelist) {
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

    public static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    public static int clamp(Integer v, int min, int max) {
        int x = v == null ? min : v;
        return Math.max(min, Math.min(max, x));
    }

    // ============================================================
    // 实体 → VO（P2：PlanService 与 PlanOrchestrator 共用，含版本元信息）
    // ============================================================

    /**
     * 把 plans 行 + plan_details 明细组装为 PlanVO，并解析 validation_report。
     * 动作要领/类别回查动作库（唯一数据源），与旧 toVO 行为一致。
     */
    public static PlanVO toVO(Plan plan, List<PlanDetail> details,
                              ExerciseRepository exerciseRepository,
                              ObjectMapper objectMapper) {
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
                    .planDetailId(d.getId())
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

        ValidationReport report = null;
        if (plan.getValidationReport() != null && !plan.getValidationReport().isBlank()) {
            try {
                report = objectMapper.readValue(plan.getValidationReport(), ValidationReport.class);
            } catch (Exception ignore) {
                // 报告损坏不影响计划主体
            }
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
                .version(plan.getVersion() == null ? 1 : plan.getVersion())
                .parentId(plan.getParentId())
                .changeNote(plan.getChangeNote())
                .sourceLink(plan.getSourceLink())
                .validationReport(report)
                .riskNotes(report == null ? List.of() : report.getRiskNotes())
                .generationMs(plan.getGenerationMs())
                .aiTokens(plan.getAiTokens())
                .build();
    }
}
