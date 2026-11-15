package com.fitlife.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.entity.DietPlanItem;
import com.fitlife.entity.FoodItem;
import com.fitlife.repository.FoodItemRepository;
import com.fitlife.repository.KnowledgeRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 饮食方案生成器（P5）。
 *
 * <p>只做确定性的营养分配，不调用大模型：先按目标热量与蛋白区间算出每日宏量目标，
 * 再按三餐比例把热量分到「主食 / 蛋白 / 蔬菜 / 水果」四类食物槽位，从 food_item
 * 表按每 100g 成分换算克数。好处是每个数字都能回溯到成分表，可复核、可测试；
 * 大模型只负责在对话里解释，不负责算热量。
 *
 * <p>单位约定：库内热量统一 kcal/100g；输出同时给 grams（可食部克数）与
 * unitLabel/householdMeasure（1 碗 / 1 个），前端据此显示「1.5 碗(225g) · 261 kcal」。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DietPlanner {

    private final FoodItemRepository foodItemRepository;
    private final KnowledgeRuleRepository knowledgeRuleRepository;

    /** 每日宏量目标（g 与 kcal） */
    public record Target(double kcal, double proteinG, double carbG, double fatG) {
    }

    /** 一餐里的一个食物槽位 */
    private record Slot(String category, double share) {
    }

    private static final List<Slot> BREAKFAST = List.of(
            new Slot("主食", 0.45), new Slot("蛋白", 0.35), new Slot("水果", 0.20));
    private static final List<Slot> LUNCH = List.of(
            new Slot("主食", 0.40), new Slot("蛋白", 0.36), new Slot("蔬菜", 0.24));
    private static final List<Slot> DINNER = List.of(
            new Slot("主食", 0.32), new Slot("蛋白", 0.38), new Slot("蔬菜", 0.30));
    private static final List<Slot> SNACK = List.of(
            new Slot("乳品", 0.60), new Slot("水果", 0.40));

    /**
     * 由身体指标推导每日宏量目标。
     *
     * <p>蛋白按目标取 g/kg（减脂 1.8–2.2、增肌 1.6–2.0、健康 1.2–1.6，取区间中值）；
     * 脂肪固定占总热量 25%；碳水吃掉剩余热量（4 kcal/g）。
     */
    public Target computeTarget(String goal, double weightKg, double kcal) {
        double perKg = proteinPerKg(goal);
        double proteinG = round1(Math.max(60.0, perKg * weightKg));
        double fatG = round1(kcal * fatRatio() / 9.0);
        double carbKcal = Math.max(0.0, kcal - proteinG * 4.0 - fatG * 9.0);
        double carbG = round1(carbKcal / 4.0);
        return new Target(round1(kcal), proteinG, carbG, fatG);
    }

    /**
     * 生成 7 天饮食明细。
     *
     * @param trainDows 训练日（1=周一），训练日多一餐加餐
     */
    public List<DietPlanItem> plan(Target target, Set<Integer> trainDows, String goal) {
        List<FoodItem> all = foodItemRepository.findByEnabledOrderByCategoryAscIdAsc(1);
        if (all.isEmpty()) {
            log.warn("food_item 表为空，跳过饮食方案生成");
            return List.of();
        }
        Map<String, List<FoodItem>> byCategory = all.stream()
                .filter(f -> f.getCategory() != null && f.getKcalPer100g() != null && f.getKcalPer100g() > 0)
                .collect(Collectors.groupingBy(FoodItem::getCategory, LinkedHashMap::new, Collectors.toList()));

        Map<String, Double> split = mealSplit();
        List<DietPlanItem> out = new ArrayList<>();
        for (int dow = 1; dow <= 7; dow++) {
            boolean train = trainDows != null && trainDows.contains(dow);
            int seq = 0;
            seq = fillMeal(out, byCategory, dow, "早餐", target.kcal * split.getOrDefault("早餐", 0.30),
                    BREAKFAST, seq, goal, dow);
            seq = fillMeal(out, byCategory, dow, "午餐", target.kcal * split.getOrDefault("午餐", 0.40),
                    LUNCH, seq, goal, dow);
            if (train) {
                seq = fillMeal(out, byCategory, dow, "加餐", target.kcal * 0.08,
                        SNACK, seq, goal, dow);
            }
            seq = fillMeal(out, byCategory, dow, "晚餐", target.kcal * split.getOrDefault("晚餐", 0.30),
                    DINNER, seq, goal, dow);
            topUpProtein(out, byCategory, dow, target.proteinG());
        }
        return out;
    }

    /** 一餐按槽位分热量并换算克数 */
    private int fillMeal(List<DietPlanItem> out, Map<String, List<FoodItem>> byCategory,
                         int dow, String meal, double mealKcal, List<Slot> slots,
                         int seq, String goal, int rotateSeed) {
        int s = seq;
        int slotIdx = 0;
        for (Slot slot : slots) {
            List<FoodItem> pool = byCategory.getOrDefault(slot.category(), List.of());
            if (pool.isEmpty()) {
                continue;
            }
            FoodItem food = pick(pool, rotateSeed, meal.hashCode() + slotIdx, goal);
            double slotKcal = mealKcal * slot.share();
            double grams = roundGrams(slotKcal / (food.getKcalPer100g() / 100.0), food, slot.category());
            out.add(buildItem(food, dow, meal, grams, ++s));
            slotIdx++;
        }
        return s;
    }

    /** 轮换取食物，保证 7 天不完全重复；同目标偏好高蛋白 / 低脂 */
    private FoodItem pick(List<FoodItem> pool, int dow, int salt, String goal) {
        List<FoodItem> sorted = new ArrayList<>(pool);
        if (goal != null && goal.contains("减脂")) {
            sorted.sort(Comparator.comparingDouble(f -> f.getFatG() == null ? 99 : f.getFatG()));
        } else if (goal != null && goal.contains("增肌")) {
            sorted.sort(Comparator.comparingDouble(f -> -(f.getProteinG() == null ? 0 : f.getProteinG())));
        }
        int idx = Math.floorMod(dow * 3 + salt, sorted.size());
        return sorted.get(idx);
    }

    /**
     * 克数取整：g 类按 5g 步进（蔬菜 20g 步进），带单位食物按整份取整，
     * 并给出上下限，避免出现「吃 3g 米饭」这种不可执行的建议。
     */
    private double roundGrams(double raw, FoodItem food, String category) {
        double min = "蔬菜".equals(category) ? 100 : ("主食".equals(category) ? 50 : 30);
        double max = "蔬菜".equals(category) ? 400 : 350;
        double g = Math.max(min, Math.min(max, raw));
        String unit = food.getUnitLabel();
        double per = food.getGramsPerUnit() == null || food.getGramsPerUnit() <= 0 ? 0 : food.getGramsPerUnit();
        if (unit != null && !"g".equalsIgnoreCase(unit) && per > 0) {
            double units = Math.max(1, Math.round(g / per));
            return round1(units * per);
        }
        double step = "蔬菜".equals(category) ? 20 : 5;
        return round1(Math.round(g / step) * step);
    }

    private DietPlanItem buildItem(FoodItem food, int dow, String meal, double grams, int seq) {
        double factor = grams / 100.0;
        return DietPlanItem.builder()
                .dayOfWeek(dow)
                .meal(meal)
                .foodId(food.getId())
                .foodName(food.getName())
                .grams(grams)
                .unitLabel(food.getUnitLabel())
                .amountDesc(amountDesc(food, grams))
                .kcal(round1(nz(food.getKcalPer100g()) * factor))
                .proteinG(round1(nz(food.getProteinG()) * factor))
                .carbG(round1(nz(food.getCarbG()) * factor))
                .fatG(round1(nz(food.getFatG()) * factor))
                .seq(seq)
                .sourceUrl(food.getSourceUrl())
                .build();
    }

    /** 「1 碗(150g)」「1.2 杯(300g)」这类可读份量 */
    private String amountDesc(FoodItem food, double grams) {
        String unit = food.getUnitLabel() == null ? "g" : food.getUnitLabel();
        double per = food.getGramsPerUnit() == null ? 0 : food.getGramsPerUnit();
        if (!"g".equalsIgnoreCase(unit) && per > 0) {
            double units = grams / per;
            String u = Math.abs(units - Math.rint(units)) < 0.05
                    ? String.valueOf((int) Math.rint(units))
                    : String.format("%.1f", units);
            return String.format("%s %s(%sg)", u, unit, fmt(grams));
        }
        return fmt(grams) + " g";
    }

    /** 蛋白不足时补一份乳清或希腊酸奶，保证达到目标蛋白的 90% */
    private void topUpProtein(List<DietPlanItem> out, Map<String, List<FoodItem>> byCategory,
                              int dow, double proteinTarget) {
        double total = out.stream().filter(i -> i.getDayOfWeek() == dow)
                .mapToDouble(i -> nz(i.getProteinG())).sum();
        if (total >= proteinTarget * 0.90) {
            return;
        }
        List<FoodItem> candidates = new ArrayList<>();
        candidates.addAll(byCategory.getOrDefault("乳品", List.of()));
        candidates.addAll(byCategory.getOrDefault("蛋白", List.of()));
        FoodItem best = candidates.stream()
                .filter(f -> nz(f.getProteinG()) > 0)
                .max(Comparator.comparingDouble(f -> nz(f.getProteinG()) / Math.max(1.0, nz(f.getKcalPer100g()))))
                .orElse(null);
        if (best == null) {
            return;
        }
        double need = proteinTarget - total;
        double grams = Math.min(60.0, need / Math.max(0.01, nz(best.getProteinG()) / 100.0));
        if (grams < 10) {
            return;
        }
        int maxSeq = out.stream().filter(i -> i.getDayOfWeek() == dow)
                .mapToInt(i -> i.getSeq() == null ? 0 : i.getSeq()).max().orElse(0);
        out.add(buildItem(best, dow, "加餐", roundGrams(grams, best, "乳品"), maxSeq + 1));
    }

    // ---------- 规则读取（缺省值与原设计一致） ----------

    private double proteinPerKg(String goal) {
        JsonNode node = dietRule();
        JsonNode perKg = node.path("proteinPerKg").path(goalKey(goal));
        if (perKg.isArray() && perKg.size() == 2) {
            return (perKg.get(0).asDouble() + perKg.get(1).asDouble()) / 2.0;
        }
        return switch (goalKey(goal)) {
            case "muscle_gain" -> 1.8;
            case "health" -> 1.4;
            default -> 2.0;
        };
    }

    /** 脂肪供能占比，默认 25%（DIET_TARGET.fatRatio 可覆盖） */
    private double fatRatio() {
        JsonNode node = dietRule();
        double v = node.path("fatRatio").asDouble(0.25);
        return (v > 0.10 && v < 0.45) ? v : 0.25;
    }

    /** 读取 DIET_TARGET 规则，失败返回空对象（全部走内置默认） */
    private JsonNode dietRule() {
        try {
            var rule = knowledgeRuleRepository.findByCode("DIET_TARGET");
            if (rule.isPresent() && rule.get().getExpression() != null) {
                return new ObjectMapper().readTree(rule.get().getExpression());
            }
        } catch (Exception e) {
            log.warn("读取 DIET_TARGET 失败，使用内置默认参数：{}", e.getMessage());
        }
        return new ObjectMapper().createObjectNode();
    }

    private Map<String, Double> mealSplit() {
        Map<String, Double> split = new HashMap<>();
        split.put("早餐", 0.30);
        split.put("午餐", 0.40);
        split.put("晚餐", 0.30);
        JsonNode node = dietRule().path("mealSplit");
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(k -> {
                double v = node.path(k).asDouble(0);
                if (v > 0) {
                    split.put(k, v);
                }
            });
        }
        return split;
    }

    private static String goalKey(String goal) {
        if (goal == null) {
            return "fat_loss";
        }
        String g = goal.trim();
        if (g.contains("增肌") || g.contains("muscle") || g.contains("塑形")) {
            return "muscle_gain";
        }
        if (g.contains("健康") || g.contains("keep") || g.contains("health") || g.contains("体能")) {
            return "health";
        }
        return "fat_loss";
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String fmt(double v) {
        return Math.abs(v - Math.rint(v)) < 0.05 ? String.valueOf((int) Math.rint(v)) : String.format("%.1f", v);
    }
}