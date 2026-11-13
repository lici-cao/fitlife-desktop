package com.fitlife.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.dto.PlanDayVO;
import com.fitlife.dto.PlanItemVO;
import com.fitlife.dto.PlanVO;
import com.fitlife.dto.ValidationReport;
import com.fitlife.entity.Exercise;
import com.fitlife.entity.KnowledgeRule;
import com.fitlife.repository.KnowledgeRuleRepository;
import com.fitlife.util.BodyMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运动处方规则校验器（P2 核心，对应图 2 第 ⑩ 步）。
 *
 * <p>六类校验：V1 靶心率 / V2 FITT-VP / V3 循序渐进 / V4 恢复间隔 /
 * V5 禁忌人群 / V6 容量预算。阈值优先读 knowledge_rule 表（可配置、可版本），
 * 读不到退回内置默认——规则表被清空也只降级、不崩溃。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanValidator {

    private final KnowledgeRuleRepository knowledgeRuleRepository;
    private final ObjectMapper objectMapper;

    /** 校验上下文：画像补全后的关键参数 */
    public record VContext(int age, Integer hrRest, int level, String goal,
                           int weeklyDays, int sessionMinutes, List<String> injuries,
                           double weightKg) {
    }

    // ---- 内置默认阈值（与 V2 迁移种子的 knowledge_rule 一致） ----
    private static final double DEF_MAX_PCT = 0.90;
    private static final int DEF_MAX_WEEKLY_INCREASE_PCT = 10;
    private static final int DEF_MIN_DAY_GAP = 2;      // 相邻训练日 gap=1 < 2 视为未恢复
    private static final int DEF_HIGH_RPE = 8;
    private static final int DEF_ITEMS_MIN = 4;
    private static final int DEF_ITEMS_MAX = 6;
    private static final int DEF_SESSION_OVERRUN_PCT = 15;
    private static final int DEF_MAX_SETS_PER_MUSCLE_WEEK = 20;

    public ValidationReport validate(PlanVO vo, Map<Long, Exercise> candidateById,
                                     VContext ctx, Map<String, Integer> parentSets) {
        ValidationReport r = new ValidationReport();
        Map<String, JsonNode> rules = loadRules();
        Map<String, Object> paramsUsed = new LinkedHashMap<>();

        List<PlanDayVO> days = vo.getDays() == null ? List.of() : vo.getDays();

        // ===== V1 靶心率 / 强度上限 =====
        JsonNode hr = rules.get("HR_TARGET");
        double maxPct = num(hr, "/maxPct", DEF_MAX_PCT);
        paramsUsed.put("maxPct", maxPct);
        int v1Errors = 0, v1Warns = 0;
        for (PlanDayVO d : days) {
            if (Boolean.TRUE.equals(d.getRestDay()) || d.getItems() == null) {
                continue;
            }
            int beginnerHits = 0;
            String beginnerFirst = null;
            for (PlanItemVO it : d.getItems()) {
                int rpe = it.getRpe() == null ? 6 : it.getRpe();
                double pct = rpeToPct(hr, rpe);
                if (pct > maxPct) {
                    int bpm = BodyMetrics.targetBpm(ctx.age(), ctx.hrRest(), pct);
                    r.add("V1", "靶心率", "ERROR", dayLabel(d) + "「" + it.getExerciseName()
                            + "」RPE=" + rpe + " 约 " + Math.round(pct * 100) + "%HRR（≈" + bpm
                            + " bpm），超过 " + Math.round(maxPct * 100) + "% 安全上限");
                    v1Errors++;
                } else if (ctx.level() == 1 && rpe >= DEF_HIGH_RPE) {
                    beginnerHits++;
                    if (beginnerFirst == null) {
                        beginnerFirst = it.getExerciseName();
                    }
                }
            }
            if (beginnerHits > 0) {
                r.add("V1", "靶心率", "WARN", dayLabel(d) + "「" + beginnerFirst + "」等 " + beginnerHits
                        + " 个动作初级用户 RPE≥" + DEF_HIGH_RPE + " 偏高，建议 5-6 起步");
                v1Warns++;
            }
        }

        // ===== V2 FITT-VP 目标区间 =====
        JsonNode fitt = rules.get("FITT");
        String goalKey = goalKey(ctx.goal());
        JsonNode g = fitt == null ? null : fitt.path(goalKey);
        int trainDays = 0;
        for (PlanDayVO d : days) {
            if (!Boolean.TRUE.equals(d.getRestDay()) && d.getItems() != null && !d.getItems().isEmpty()) {
                trainDays++;
            }
        }
        int[] daysRange = g == null ? new int[]{2, 6}
                : new int[]{g.path("days").path(0).asInt(2), g.path("days").path(1).asInt(6)};
        paramsUsed.put("fitt." + goalKey + ".days", daysRange[0] + "-" + daysRange[1]);
        if (trainDays < daysRange[0] || trainDays > daysRange[1]) {
            r.add("V2", "FITT-VP", "WARN", "训练日 " + trainDays + " 天/周，超出目标区间 "
                    + daysRange[0] + "-" + daysRange[1] + " 天");
        }
        int cardioMinWeek = g == null ? 0 : g.path("cardioMinWeek").asInt(0);
        paramsUsed.put("fitt.cardioMinWeek", cardioMinWeek);
        int cardioMin = vo.getWeeklyCardioMin() == null ? 0 : vo.getWeeklyCardioMin();
        if (cardioMinWeek > 0 && cardioMin < cardioMinWeek) {
            r.add("V2", "FITT-VP", "WARN", "每周有氧 " + cardioMin + " 分钟，低于该目标建议的 "
                    + cardioMinWeek + " 分钟（如 150 分钟健康基线）");
        }
        if ("muscle_gain".equals(goalKey)) {
            int freqMin = g == null ? 2 : g.path("majorMuscleFreqMin").asInt(2);
            paramsUsed.put("fitt.majorMuscleFreqMin", freqMin);
            for (String muscle : List.of("胸", "背", "腿")) {
                int freq = countMuscleDays(days, muscle);
                if (trainDays >= 3 && freq < freqMin) {
                    r.add("V2", "FITT-VP", "WARN", "增肌目标下大肌群「" + muscle + "」每周仅练 "
                            + freq + " 次，建议 ≥" + freqMin + " 次");
                }
            }
        }

        // ===== V3 循序渐进：周容量环比 =====
        JsonNode prog = rules.get("PROGRESSION");
        int maxInc = intNum(prog, "/maxWeeklyIncreasePct", DEF_MAX_WEEKLY_INCREASE_PCT);
        paramsUsed.put("maxWeeklyIncreasePct", maxInc);
        int curTotalSets = totalSets(days);
        if (parentSets != null && !parentSets.isEmpty()) {
            int parentTotal = parentSets.values().stream().mapToInt(Integer::intValue).sum();
            if (parentTotal > 0) {
                double inc = (curTotalSets - parentTotal) * 100.0 / parentTotal;
                if (inc > maxInc) {
                    r.add("V3", "循序渐进", "ERROR", "周总组数 " + parentTotal + " → " + curTotalSets
                            + "，环比 +" + Math.round(inc) + "%，超过每周增幅上限 " + maxInc + "%");
                }
            }
        }

        // ===== V4 恢复间隔：同肌群相邻训练日 =====
        JsonNode rec = rules.get("RECOVERY");
        int minGap = intNum(rec, "/minDayGap", DEF_MIN_DAY_GAP);
        int highRpe = intNum(rec, "/highIntensityRpe", DEF_HIGH_RPE);
        paramsUsed.put("minDayGap", minGap);
        Map<String, List<Integer>> muscleDays = new LinkedHashMap<>();
        Map<String, Integer> muscleMaxRpe = new HashMap<>();
        for (PlanDayVO d : days) {
            if (Boolean.TRUE.equals(d.getRestDay()) || d.getItems() == null) {
                continue;
            }
            for (PlanItemVO it : d.getItems()) {
                String mg = it.getMuscleGroup();
                if (mg == null || mg.isBlank() || "全身".equals(mg) || "核心".equals(mg)) {
                    continue;
                }
                int dow = d.getDayOfWeek() == null ? 1 : d.getDayOfWeek();
                List<Integer> list = muscleDays.computeIfAbsent(mg, k -> new ArrayList<>());
                if (!list.contains(dow)) {
                    list.add(dow);
                }
                int rpe = it.getRpe() == null ? 6 : it.getRpe();
                muscleMaxRpe.merge(mg, rpe, Math::max);
            }
        }
        for (Map.Entry<String, List<Integer>> en : muscleDays.entrySet()) {
            List<Integer> ds = en.getValue();
            ds.sort(Integer::compareTo);
            for (int i = 1; i < ds.size(); i++) {
                int gap = ds.get(i) - ds.get(i - 1);
                if (gap < minGap) {
                    String lvl = muscleMaxRpe.getOrDefault(en.getKey(), 6) >= highRpe ? "ERROR" : "WARN";
                    r.add("V4", "恢复间隔", lvl, "肌群「" + en.getKey() + "」在周" + ds.get(i - 1)
                            + "与周" + ds.get(i) + "连续训练，间隔不足 " + minGap + " 天"
                            + ("ERROR".equals(lvl) ? "且均为高强度（RPE≥" + highRpe + "）" : ""));
                }
            }
        }

        // ===== V5 禁忌人群：候选过滤后的第二道防线 =====
        JsonNode ci = rules.get("CONTRAINDICATION");
        String ciSeverity = ci == null || ci.path("errorSeverity").isMissingNode() ? "ERROR"
                : ci.path("errorSeverity").asText("ERROR");
        paramsUsed.put("contraindicationSeverity", ciSeverity);
        if (ctx.injuries() != null) {
            for (PlanDayVO d : days) {
                if (d.getItems() == null) {
                    continue;
                }
                for (PlanItemVO it : d.getItems()) {
                    Exercise ex = it.getExerciseId() == null ? null : candidateById.get(it.getExerciseId());
                    String contra = ex == null ? null : ex.getContraindication();
                    if (contra == null || contra.isBlank()) {
                        continue;
                    }
                    for (String inj : ctx.injuries()) {
                        if (inj.isBlank()) {
                            continue;
                        }
                        String token = inj.replaceAll("[关节部位患老]", "").trim();
                        if (token.isEmpty()) {
                            token = inj;
                        }
                        if (contra.contains(token) || contra.contains(inj)) {
                            r.add("V5", "禁忌人群", ciSeverity, dayLabel(d) + "「" + it.getExerciseName()
                                    + "」禁忌字段「" + contra + "」命中用户伤病「" + inj + "」");
                        }
                    }
                }
            }
        }

        // ===== V6 容量预算 =====
        JsonNode cap = rules.get("CAPACITY");
        int itemsMin = intNum(cap, "/itemsPerSession/0", DEF_ITEMS_MIN);
        int itemsMax = intNum(cap, "/itemsPerSession/1", DEF_ITEMS_MAX);
        int overrunPct = intNum(cap, "/sessionOverrunPct", DEF_SESSION_OVERRUN_PCT);
        int maxSetsMuscle = intNum(cap, "/maxSetsPerMuscleWeek", DEF_MAX_SETS_PER_MUSCLE_WEEK);
        paramsUsed.put("itemsPerSession", itemsMin + "-" + itemsMax);
        paramsUsed.put("sessionOverrunPct", overrunPct);
        paramsUsed.put("maxSetsPerMuscleWeek", maxSetsMuscle);
        Map<String, Integer> weekSets = new LinkedHashMap<>();
        for (PlanDayVO d : days) {
            if (Boolean.TRUE.equals(d.getRestDay()) || d.getItems() == null || d.getItems().isEmpty()) {
                continue;
            }
            int n = d.getItems().size();
            if (n < itemsMin || n > itemsMax) {
                r.add("V6", "容量预算", "WARN", dayLabel(d) + "安排 " + n + " 个动作，建议 "
                        + itemsMin + "-" + itemsMax + " 个");
            }
            int budget = ctx.sessionMinutes() > 0 ? ctx.sessionMinutes() : 45;
            if (d.getEstMinutes() != null && d.getEstMinutes() > budget * (100 + overrunPct) / 100.0) {
                r.add("V6", "容量预算", "WARN", dayLabel(d) + "预计 " + d.getEstMinutes()
                        + " 分钟，超出预算 " + budget + " 分钟的 " + overrunPct + "%");
            }
            for (PlanItemVO it : d.getItems()) {
                if (it.getMuscleGroup() != null && it.getSets() != null) {
                    weekSets.merge(it.getMuscleGroup(), it.getSets(), Integer::sum);
                }
            }
        }
        for (Map.Entry<String, Integer> en : weekSets.entrySet()) {
            if (en.getValue() > maxSetsMuscle) {
                r.add("V6", "容量预算", "WARN", "肌群「" + en.getKey() + "」周组数 " + en.getValue()
                        + "，超过建议上限 " + maxSetsMuscle + " 组");
            }
        }

        // ===== 汇总 =====
        long errors = r.getChecks().stream().filter(c -> "ERROR".equals(c.getLevel())).count();
        long warns = r.getChecks().stream().filter(c -> "WARN".equals(c.getLevel())).count();
        r.setPassed(errors == 0);
        r.setScore(Math.max(0, (int) (100 - 15 * errors - 5 * warns)));
        r.setParamsUsed(paramsUsed);

        // 风险提示：无论通过与否都给（可解释性）
        int rest = BodyMetrics.hrRestOrDefault(ctx.hrRest());
        int zoneLow = BodyMetrics.targetBpm(ctx.age(), ctx.hrRest(), 0.50);
        int zoneHigh = BodyMetrics.targetBpm(ctx.age(), ctx.hrRest(), 0.70);
        r.getRiskNotes().add("中等强度靶心率参考区间 " + zoneLow + "–" + zoneHigh + " bpm（HRmax≈"
                + BodyMetrics.hrMax(ctx.age()) + "、HRrest=" + rest + "，Karvonen 法）");
        r.getRiskNotes().add("如出现关节疼痛、静息心率上升或睡眠变差，先减量再进阶（每周增幅≤10%）");
        if (!ctx.injuries().isEmpty()) {
            r.getRiskNotes().add("已按伤病「" + String.join("、", ctx.injuries()) + "」过滤并校验禁忌动作，训练中请保持动作幅度保守");
        }
        return r;
    }

    // ------------------------------------------------------------------

    private Map<String, JsonNode> loadRules() {
        Map<String, JsonNode> out = new HashMap<>();
        try {
            for (KnowledgeRule rule : knowledgeRuleRepository.findByEnabled(1)) {
                if (rule.getExpression() == null || rule.getExpression().isBlank()) {
                    continue;
                }
                try {
                    out.put(rule.getCode(), objectMapper.readTree(rule.getExpression()));
                } catch (Exception ignore) {
                    log.warn("规则 {} 的 expression 不是合法 JSON，使用内置默认", rule.getCode());
                }
            }
        } catch (Exception e) {
            log.warn("规则表读取失败，全部使用内置默认阈值: {}", e.getMessage());
        }
        return out;
    }

    private double rpeToPct(JsonNode hr, int rpe) {
        if (hr != null) {
            JsonNode map = hr.path("rpeToHrr").path(String.valueOf(rpe));
            if (map.isNumber()) {
                return map.asDouble();
            }
        }
        return BodyMetrics.rpeToHrrPct(rpe);
    }

    private static double num(JsonNode node, String pointer, double def) {
        if (node == null) {
            return def;
        }
        JsonNode v = node.at(pointer);
        return v.isNumber() ? v.asDouble() : def;
    }

    private static int intNum(JsonNode node, String pointer, int def) {
        return (int) num(node, pointer, def);
    }

    private static String goalKey(String goal) {
        String s = goal == null ? "" : goal.toLowerCase();
        if (s.contains("fat") || s.contains("减脂")) {
            return "fat_loss";
        }
        if (s.contains("muscle") || s.contains("gain") || s.contains("增肌")) {
            return "muscle_gain";
        }
        return "health";
    }

    private static int countMuscleDays(List<PlanDayVO> days, String muscleToken) {
        int count = 0;
        for (PlanDayVO d : days) {
            if (Boolean.TRUE.equals(d.getRestDay()) || d.getItems() == null) {
                continue;
            }
            boolean hit = d.getItems().stream()
                    .anyMatch(it -> it.getMuscleGroup() != null && it.getMuscleGroup().contains(muscleToken));
            if (hit) {
                count++;
            }
        }
        return count;
    }

    private static int totalSets(List<PlanDayVO> days) {
        int sum = 0;
        for (PlanDayVO d : days) {
            if (d.getItems() == null) {
                continue;
            }
            for (PlanItemVO it : d.getItems()) {
                sum += it.getSets() == null ? 0 : it.getSets();
            }
        }
        return sum;
    }

    /** 供修复器复用：本周各肌群组数 */
    public static Map<String, Integer> weeklySets(PlanVO vo) {
        Map<String, Integer> weekSets = new LinkedHashMap<>();
        if (vo == null || vo.getDays() == null) {
            return weekSets;
        }
        for (PlanDayVO d : vo.getDays()) {
            if (d.getItems() == null) {
                continue;
            }
            for (PlanItemVO it : d.getItems()) {
                if (it.getMuscleGroup() != null && it.getSets() != null) {
                    weekSets.merge(it.getMuscleGroup(), it.getSets(), Integer::sum);
                }
            }
        }
        return weekSets;
    }

    private static String dayLabel(PlanDayVO d) {
        return d.getDayLabel() == null ? ("周" + d.getDayOfWeek()) : d.getDayLabel();
    }
}
