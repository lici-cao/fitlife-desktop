package com.fitlife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.dto.*;
import com.fitlife.entity.Exercise;
import com.fitlife.entity.UserProfile;
import com.fitlife.repository.ExerciseRepository;
import com.fitlife.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 让 AI 从「只会聊天」升级为「能办事」：
 *
 * <ol>
 *   <li><b>对话式计划生成</b>：识别自然语言里的目标/天数/时长/器械/伤病，
 *       调用 {@link PlanService} 生成并落库一周训练计划；</li>
 *   <li><b>智能运动搭配</b>：按时间预算从动作库挑选一组真实动作，
 *       输出带组次、要领、MET 与热量估算的结构化动作卡；</li>
 *   <li><b>普通对话</b>：走检索增强（RAG），回答里只引用动作库中的真实动作。</li>
 * </ol>
 *
 * <p>整条链路都不依赖大模型也能工作：AI 不可用时自动降级为规则引擎，
 * 因此不会出现「问了没反应」的情况。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiPlanService {

    private final ExerciseRepository exerciseRepository;
    private final UserProfileRepository profileRepository;
    private final PlanService planService;
    private final AiService aiService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> EQUIPMENT_WHITELIST = new LinkedHashSet<>(
            Arrays.asList("徒手", "哑铃", "杠铃", "器械", "弹力带", "壶铃"));
    private static final List<String> ALL_EQUIPMENT = new ArrayList<>(EQUIPMENT_WHITELIST);

    private static final Pattern DAYS_PATTERN = Pattern.compile("(?:每周|一周|每星期|周)\\s*([1-7一二三四五六七])\\s*天|([1-7])\\s*天");
    private static final Pattern MINUTES_PATTERN = Pattern.compile("(\\d{1,3})\\s*(?:分钟|min|分)");

    // ============================================================
    // 一、对话入口
    // ============================================================

    public PlanChatResponse chat(Long userId, String message, Boolean force) {
        if (message == null || message.isBlank()) {
            return PlanChatResponse.builder()
                    .action("CHAT").reply("说点什么吧，比如「帮我生成一周减脂计划」或「今天练胸怎么搭配」").build();
        }
        String text = message.trim();
        Intent intent = parseIntent(text, userId);

        // 1) 2) 先看能不能直接办事：生成周计划 / 智能运动搭配
        PlanChatResponse action = handleAction(intent, userId, force == null || force);
        if (action != null) {
            return action;
        }

        // 3) 普通聊天（后端已做动作库检索增强）
        AiChatRequest aiReq = new AiChatRequest();
        aiReq.setMessage(text);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("goal", intent.goal);
        context.put("equipments", intent.equipments);
        context.put("injuries", intent.injuries);
        aiReq.setContext(context);
        try {
            AiChatResponse ai = aiService.chat(aiReq);
            String hint = "\n\n想直接落地？试试说「帮我生成一周减脂计划」或「今天练胸怎么搭配」。";
            return PlanChatResponse.builder().action("CHAT")
                    .reply((ai.getReply() == null ? "" : ai.getReply()) + hint).build();
        } catch (Exception e) {
            log.warn("AI 对话失败: {}", e.getMessage());
            return PlanChatResponse.builder().action("CHAT")
                    .reply("AI 服务暂时不可用，不过我依然可以帮你办事：\n"
                            + "· 说「生成一周减脂计划」→ 直接生成并保存训练计划\n"
                            + "· 说「今天练胸怎么搭配」→ 从动作库搭配一组动作\n"
                            + "· 或到「动作库」页浏览全部 100 个动作。")
                    .build();
        }
    }

    /**
     * 流式对话接口复用：只处理「生成计划 / 运动搭配」这两类可执行意图。
     * 没有命中意图时返回 empty，交给大模型自由对话。
     */
    public Optional<PlanChatResponse> tryAction(Long userId, String message, boolean force) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        Intent intent = parseIntent(message.trim(), userId);
        return Optional.ofNullable(handleAction(intent, userId, force));
    }

    /** 命中可执行意图则执行并返回结果，否则返回 null */
    private PlanChatResponse handleAction(Intent intent, Long userId, boolean force) {
        // 1) 生成周计划
        if (intent.wantPlan) {
            GeneratePlanRequest req = new GeneratePlanRequest();
            req.setGoal(intent.goal);
            req.setExperience(intent.experience);
            req.setWeeklyDays(intent.weeklyDays);
            req.setSessionMinutes(intent.minutes);
            req.setEquipments(String.join(",", intent.equipments));
            req.setInjuries(String.join(",", intent.injuries));
            req.setForce(force);

            try {
                ApiResponse resp = planService.generatePlan(userId, req);
                if (!resp.isSuccess() || !(resp.getData() instanceof PlanVO)) {
                    return PlanChatResponse.builder().action("CHAT")
                            .reply("计划生成失败：" + resp.getMessage()
                                    + "。请确认动作库已初始化（重启后端或执行 fitlife-mysql.sql）。")
                            .build();
                }
                PlanVO plan = (PlanVO) resp.getData();
                String reply = "已为你生成并保存《" + plan.getTitle() + "》\n"
                        + "· 目标：" + intent.goal + "，每周 " + intent.weeklyDays + " 天，每次约 " + intent.minutes + " 分钟\n"
                        + "· 可用器械：" + String.join("、", intent.equipments) + "\n"
                        + "· 伤病规避：" + (intent.injuries.isEmpty() ? "无" : String.join("、", intent.injuries)) + "\n"
                        + "· 生成方式：" + (Boolean.TRUE.equals(plan.getAiGenerated()) ? "大模型编排" : "内置规则模板") + "\n"
                        + "计划已存到「训练计划」页，可以随时查看每天的动作与组次。";
                return PlanChatResponse.builder().action("PLAN_CREATED").reply(reply).plan(plan).build();
            } catch (Exception e) {
                log.warn("对话生成计划失败: {}", e.getMessage());
                return PlanChatResponse.builder().action("CHAT")
                        .reply("计划生成遇到异常：" + e.getMessage()).build();
            }
        }

        // 2) 智能运动搭配
        if (intent.wantMatch) {
            WorkoutMatchRequest req = new WorkoutMatchRequest();
            req.setGoal(intent.goal);
            req.setMinutes(intent.minutes);
            req.setExperience(intent.experience);
            req.setEquipments(String.join(",", intent.equipments));
            req.setInjuries(String.join(",", intent.injuries));
            req.setMuscleGroup(intent.muscleGroup);
            try {
                WorkoutMatchResponse match = match(userId, req);
                return PlanChatResponse.builder().action("MATCH")
                        .reply("按你的条件从动作库里挑了一组 " + match.getTotalMinutes() + " 分钟的训练搭配，"
                                + "动作都来自数据库，可直接照着练。")
                        .match(match).build();
            } catch (Exception e) {
                log.warn("对话运动搭配失败: {}", e.getMessage());
                return PlanChatResponse.builder().action("CHAT")
                        .reply("运动搭配失败：" + e.getMessage()).build();
            }
        }

        return null;
    }

    // ============================================================
    // 二、智能运动搭配
    // ============================================================

    public WorkoutMatchResponse match(Long userId, WorkoutMatchRequest req) {
        UserProfile profile = profileRepository.findByUserId(userId).orElse(null);

        String goal = firstNonBlank(req.getGoal(), mapProfileGoal(profile), "保持健康");
        int minutes = clamp(req.getMinutes() != null ? req.getMinutes() : 45, 20, 120);
        int level = experienceToLevel(firstNonBlank(req.getExperience(),
                profile != null ? profile.getExperience() : null, "初级"));
        Set<String> equipments = parseCsv(firstNonBlank(req.getEquipments(),
                profile != null ? profile.getEquipments() : null, "徒手"), EQUIPMENT_WHITELIST);
        Set<String> injuries = parseCsv(firstNonBlank(req.getInjuries(),
                profile != null ? profile.getInjuries() : null, ""), null);
        double weight = req.getWeight() != null ? req.getWeight()
                : (profile != null && profile.getWeight() != null ? profile.getWeight() : 65.0);
        String focusMuscle = firstNonBlank(req.getMuscleGroup(), "", "");

        List<Exercise> pool = exerciseRepository.findCandidates(level, equipments).stream()
                .filter(e -> !hitContraindication(e, injuries))
                .collect(Collectors.toList());
        if (pool.isEmpty()) {
            throw new IllegalStateException("当前器械与伤病条件下没有可用动作，请放宽器械条件或检查动作库是否已初始化");
        }

        boolean isCut = isCutGoal(goal);
        Map<String, List<Exercise>> byCategory = pool.stream()
                .collect(Collectors.groupingBy(Exercise::getCategory, LinkedHashMap::new, Collectors.toList()));

        List<Exercise> strength = byCategory.getOrDefault("力量", Collections.emptyList());
        List<Exercise> core = byCategory.getOrDefault("核心", Collections.emptyList());
        List<Exercise> cardio = new ArrayList<>();
        cardio.addAll(byCategory.getOrDefault("有氧", Collections.emptyList()));
        cardio.addAll(byCategory.getOrDefault("HIIT", Collections.emptyList()));
        List<Exercise> mobility = byCategory.getOrDefault("柔韧", Collections.emptyList());

        // 按目标肌群优先排序主项
        List<Exercise> mainPool = new ArrayList<>(strength);
        if (!focusMuscle.isEmpty()) {
            mainPool.sort(Comparator.comparingInt((Exercise e) ->
                    focusMuscle.equals(e.getMuscleGroup()) ? 0 : 1).thenComparing(Exercise::getId));
        }

        List<WorkoutMatchResponse.MatchedExercise> items = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        int budget = minutes;
        int usedMinutes = 0;

        // 热身
        usedMinutes += addPhase(items, used, mobility, "热身", 1, "3分钟", 3, weight, budget - usedMinutes, level);
        // 主项 2-3 个（尽量覆盖不同肌群）
        int mainCount = minutes >= 60 ? 3 : 2;
        usedMinutes += addPhase(items, used, mainPool, "主项", mainCount, null, 0, weight, budget - usedMinutes, level);
        // 辅助
        usedMinutes += addPhase(items, used, strength, "辅助", 1, null, 0, weight, budget - usedMinutes, level);
        // 核心
        usedMinutes += addPhase(items, used, core, "核心", 1, null, 0, weight, budget - usedMinutes, level);
        // 有氧（减脂或时间充裕时）
        if ((isCut || minutes >= 60) && !cardio.isEmpty()) {
            usedMinutes += addPhase(items, used, cardio, "有氧", 1, "10分钟", 10, weight, budget - usedMinutes, level);
        }
        // 拉伸放松
        usedMinutes += addPhase(items, used, mobility, "拉伸", 1, "3分钟", 3, weight, budget - usedMinutes, level);

        if (items.isEmpty()) {
            throw new IllegalStateException("未能从动作库组合出训练内容，请放宽条件");
        }

        double totalCal = items.stream()
                .mapToDouble(i -> i.getEstCalories() == null ? 0 : i.getEstCalories()).sum();

        String focus = (focusMuscle.isEmpty() ? "全身综合" : focusMuscle + "专项")
                + " · " + goal + " · " + minutes + "分钟";

        // AI 只负责写编排说明，不改动作；失败则用规则说明
        String summary;
        boolean aiGenerated = false;
        String note = "已使用内置规则完成搭配";
        try {
            String prompt = buildMatchPrompt(goal, minutes, level, equipments, injuries, items);
            summary = aiService.callAi(prompt, 0.5, 500);
            if (summary != null && !summary.isBlank()) {
                summary = summary.trim();
                aiGenerated = true;
                note = "由大模型结合动作库生成搭配说明";
            } else {
                summary = ruleSummary(goal, minutes, items, injuries);
            }
        } catch (Exception e) {
            log.warn("AI 搭配说明失败，降级为规则说明: {}", e.getMessage());
            summary = ruleSummary(goal, minutes, items, injuries);
            note = "AI 服务暂不可用，已使用内置规则：" + e.getMessage();
        }

        return WorkoutMatchResponse.builder()
                .summary(summary)
                .focus(focus)
                .totalMinutes(usedMinutes)
                .estCalories(Math.round(totalCal * 10) / 10.0)
                .aiGenerated(aiGenerated)
                .note(note)
                .items(items)
                .build();
    }

    /**
     * 从候选池里挑一个未使用、且时长不超过剩余预算的动作，加入结果。
     * 返回该动作占用的分钟数（0 表示未添加）。
     */
    private int addPhase(List<WorkoutMatchResponse.MatchedExercise> items, Set<Long> used,
                         List<Exercise> pool, String phase, int count, String fixedReps,
                         int fixedMinutes, double weight, int remaining, int level) {
        if (pool.isEmpty() || remaining <= 0 || count <= 0) {
            return 0;
        }
        int addedMinutes = 0;
        int need = count;
        for (Exercise e : pool) {
            if (need <= 0) {
                break;
            }
            if (used.contains(e.getId())) {
                continue;
            }
            int sets = "有氧".equals(e.getCategory()) ? 1 : (level >= 3 ? 4 : 3);
            String reps = fixedReps != null ? fixedReps
                    : (e.getDefaultReps() == null ? "10-12" : e.getDefaultReps());
            int mins = fixedMinutes > 0 ? fixedMinutes : estimateMinutes(e, sets, reps);
            if (mins > remaining - addedMinutes) {
                continue;
            }
            double cal = estimateCalories(e, weight, mins);
            items.add(WorkoutMatchResponse.MatchedExercise.builder()
                    .exerciseId(e.getId())
                    .name(e.getName())
                    .category(e.getCategory())
                    .muscleGroup(e.getMuscleGroup())
                    .equipment(e.getEquipment())
                    .difficulty(e.getDifficulty())
                    .metValue(e.getMetValue())
                    .phase(phase)
                    .sets(sets)
                    .reps(reps)
                    .restSec("有氧".equals(e.getCategory()) || "柔韧".equals(e.getCategory()) ? 0 : (level >= 3 ? 90 : 60))
                    .minutes(mins)
                    .estCalories(cal)
                    .description(e.getDescription())
                    .contraindication(e.getContraindication())
                    .build());
            used.add(e.getId());
            addedMinutes += mins;
            need--;
        }
        return addedMinutes;
    }

    private String buildMatchPrompt(String goal, int minutes, int level,
                                    Set<String> equipments, Set<String> injuries,
                                    List<WorkoutMatchResponse.MatchedExercise> items) throws Exception {
        List<Map<String, Object>> brief = new ArrayList<>();
        for (WorkoutMatchResponse.MatchedExercise i : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phase", i.getPhase());
            m.put("name", i.getName());
            m.put("muscle", i.getMuscleGroup());
            m.put("sets", i.getSets());
            m.put("reps", i.getReps());
            brief.add(m);
        }
        return "你是一名健身教练。下面是从系统动作库中已经挑选好的一组训练动作（不要增删改动作）：\n"
                + objectMapper.writeValueAsString(brief) + "\n\n"
                + "用户目标：" + goal + "；本次时长：" + minutes + " 分钟；经验等级：" + level
                + "；可用器械：" + String.join("、", equipments)
                + "；禁忌部位：" + (injuries.isEmpty() ? "无" : String.join("、", injuries)) + "。\n"
                + "请用 2-3 句中文说明这组动作的编排思路、执行顺序要点和安全提醒，"
                + "不要输出 JSON、不要 markdown 代码块、不要列清单。";
    }

    private String ruleSummary(String goal, int minutes,
                               List<WorkoutMatchResponse.MatchedExercise> items, Set<String> injuries) {
        long mainCount = items.stream().filter(i -> "主项".equals(i.getPhase())).count();
        String injuryTip = injuries.isEmpty() ? "" : " 已按你的禁忌部位（" + String.join("、", injuries)
                + "）过滤掉高风险动作。";
        return "这组动作按「热身 → 主项 → 辅助 → 核心 → 有氧 → 拉伸」编排，共 "
                + items.size() + " 个动作、约 " + minutes + " 分钟，其中主项 " + mainCount + " 个。"
                + "先完成复合动作再补充孤立与核心训练，组间休息按要求执行；动作全部取自系统动作库，"
                + "训练前请热身，出现关节不适立即停止。" + injuryTip
                + " 目标是「" + goal + "」，建议每周保持 3-5 次并记录热量变化。";
    }

    // ============================================================
    // 三、自然语言意图解析
    // ============================================================

    private static class Intent {
        boolean wantPlan;
        boolean wantMatch;
        String goal = "保持健康";
        String experience = "初级";
        int weeklyDays = 3;
        int minutes = 45;
        String muscleGroup = "";
        Set<String> equipments = new LinkedHashSet<>();
        Set<String> injuries = new LinkedHashSet<>();
    }

    private Intent parseIntent(String text, Long userId) {
        Intent in = new Intent();
        UserProfile profile = profileRepository.findByUserId(userId).orElse(null);

        in.wantPlan = containsAny(text, "计划", "课表", "排一周", "安排一周", "训练安排")
                || (containsAny(text, "生成", "制定", "给我一份") && containsAny(text, "训练", "一周", "每周"));
        in.wantMatch = containsAny(text, "搭配", "怎么练", "如何练", "动作推荐", "推荐动作",
                "动作组合", "今天练", "练什么", "练胸", "练背", "练腿", "练肩", "练腹", "练臀")
                || text.contains("给我几个") || text.contains("推荐几个");

        // 目标
        if (containsAny(text, "减脂", "减肥", "瘦身", "燃脂", "掉秤")) in.goal = "减脂";
        else if (containsAny(text, "增肌", "练肌肉", "变壮", "长肌肉")) in.goal = "增肌";
        else if (text.contains("塑形")) in.goal = "塑形";
        else if (containsAny(text, "体能", "耐力", "心肺")) in.goal = "体能";
        else in.goal = mapProfileGoal(profile);

        // 经验
        if (containsAny(text, "高级", "进阶", "老手", "有经验")) in.experience = "高级";
        else if (containsAny(text, "中级", "有一定基础")) in.experience = "中级";
        else if (containsAny(text, "初级", "新手", "小白", "零基础")) in.experience = "初级";
        else if (profile != null && profile.getExperience() != null && !profile.getExperience().isBlank()) {
            in.experience = profile.getExperience();
        }

        // 每周天数
        Matcher md = DAYS_PATTERN.matcher(text);
        if (md.find()) {
            String raw = md.group(1) != null ? md.group(1) : md.group(2);
            in.weeklyDays = clamp(parseChineseDigit(raw), 1, 7);
        } else if (profile != null && profile.getWeeklyDays() != null) {
            in.weeklyDays = clamp(profile.getWeeklyDays(), 1, 7);
        }

        // 单次时长
        Matcher mm = MINUTES_PATTERN.matcher(text);
        if (mm.find()) {
            in.minutes = clamp(Integer.parseInt(mm.group(1)), 15, 120);
        } else if (profile != null && profile.getSessionMinutes() != null) {
            in.minutes = clamp(profile.getSessionMinutes(), 15, 120);
        }

        // 器械
        if (containsAny(text, "健身房")) {
            in.equipments.addAll(ALL_EQUIPMENT);
        } else {
            for (String eq : ALL_EQUIPMENT) {
                if (text.contains(eq)) in.equipments.add(eq);
            }
            if (containsAny(text, "在家", "家里", "无器械", "徒手训练", "不用器械")) {
                in.equipments.add("徒手");
            }
        }
        if (in.equipments.isEmpty()) {
            in.equipments.addAll(parseCsv(profile != null ? profile.getEquipments() : null, EQUIPMENT_WHITELIST));
        }
        if (in.equipments.isEmpty()) in.equipments.add("徒手");

        // 禁忌部位
        if (containsAny(text, "膝盖", "膝关节", "膝伤")) in.injuries.add("膝关节");
        if (containsAny(text, "腰", "腰椎")) in.injuries.add("腰椎");
        if (containsAny(text, "肩", "肩关节")) in.injuries.add("肩关节");
        if (containsAny(text, "手腕", "腕关节")) in.injuries.add("腕关节");
        if (in.injuries.isEmpty() && profile != null) {
            in.injuries.addAll(parseCsv(profile.getInjuries(), null));
        }

        // 指定肌群
        for (String m : Arrays.asList("胸", "背", "肩", "手臂", "腿", "臀", "核心", "腹")) {
            if (text.contains("练" + m) || text.contains(m + "部训练")) {
                in.muscleGroup = "腹".equals(m) ? "核心" : m;
                break;
            }
        }
        return in;
    }

    // ============================================================
    // 四、工具方法
    // ============================================================

    private int estimateMinutes(Exercise e, int sets, String reps) {
        String cat = e.getCategory();
        if ("有氧".equals(cat)) return clamp(parseMinutes(reps, 20), 5, 60);
        if ("HIIT".equals(cat)) return clamp(parseMinutes(reps, 10) * Math.max(sets, 1), 5, 30);
        if ("柔韧".equals(cat)) return 3;
        return clamp((int) Math.round(sets * 1.75 + Math.max(sets - 1, 0) * 1.0), 5, 30);
    }

    private double estimateCalories(Exercise e, double weightKg, int minutes) {
        if (e.getMetValue() == null) return 0.0;
        return Math.round(e.getMetValue() * weightKg * (minutes / 60.0) * 1.05 * 10) / 10.0;
    }

    private boolean hitContraindication(Exercise e, Set<String> injuries) {
        if (injuries.isEmpty() || e.getContraindication() == null || e.getContraindication().isBlank()) {
            return false;
        }
        for (String inj : injuries) {
            if (!inj.isBlank() && e.getContraindication().contains(inj)) return true;
        }
        return false;
    }

    private String mapProfileGoal(UserProfile profile) {
        if (profile == null || profile.getGoal() == null || profile.getGoal().isBlank()) return "保持健康";
        String g = profile.getGoal().toLowerCase();
        if (g.contains("fat") || g.contains("loss") || g.contains("减")) return "减脂";
        if (g.contains("muscle") || g.contains("gain") || g.contains("增")) return "增肌";
        if (g.contains("塑")) return "塑形";
        return "保持健康";
    }

    private boolean isCutGoal(String goal) {
        if (goal == null) return false;
        String g = goal.toLowerCase();
        return g.contains("减脂") || g.contains("塑形") || g.contains("fat") || g.contains("loss");
    }

    private int experienceToLevel(String experience) {
        if (experience == null) return 1;
        if (experience.contains("高") || experience.contains("进阶")) return 3;
        if (experience.contains("中")) return 2;
        return 1;
    }

    private Set<String> parseCsv(String csv, Set<String> whitelist) {
        Set<String> out = new LinkedHashSet<>();
        if (csv != null) {
            for (String s : csv.split("[,，、;；\\s]+")) {
                String t = s.trim();
                if (!t.isEmpty() && (whitelist == null || whitelist.contains(t))) out.add(t);
            }
        }
        if (whitelist != null && out.isEmpty()) out.add("徒手");
        return out;
    }

    private String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    private boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) return true;
        }
        return false;
    }

    private int parseChineseDigit(String raw) {
        if (raw == null) return 3;
        switch (raw) {
            case "一": return 1;
            case "二": return 2;
            case "三": return 3;
            case "四": return 4;
            case "五": return 5;
            case "六": return 6;
            case "七": return 7;
            default:
                try { return Integer.parseInt(raw); } catch (NumberFormatException e) { return 3; }
        }
    }

    private int parseMinutes(String text, int fallback) {
        if (text == null) return fallback;
        Matcher m = Pattern.compile("(\\d+)").matcher(text);
        return m.find() ? Math.max(1, Integer.parseInt(m.group(1))) : fallback;
    }

    private int clamp(Integer v, int min, int max) {
        int x = v == null ? min : v;
        return Math.max(min, Math.min(max, x));
    }
}
