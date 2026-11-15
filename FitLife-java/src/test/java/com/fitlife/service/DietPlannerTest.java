package com.fitlife.service;

import com.fitlife.entity.DietPlanItem;
import com.fitlife.entity.FoodItem;
import com.fitlife.entity.KnowledgeRule;
import com.fitlife.repository.FoodItemRepository;
import com.fitlife.repository.KnowledgeRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 饮食方案的数值验收：这是 P5 唯一"会算数"的模块，必须用测试把
 * 「总热量贴近目标、蛋白达标、单位与克数都在、训练日多一餐」钉死。
 */
class DietPlannerTest {

    private FoodItemRepository foodRepo;
    private KnowledgeRuleRepository ruleRepo;
    private DietPlanner planner;

    @BeforeEach
    void setUp() {
        foodRepo = Mockito.mock(FoodItemRepository.class);
        ruleRepo = Mockito.mock(KnowledgeRuleRepository.class);
        Mockito.when(ruleRepo.findByCode(Mockito.anyString())).thenReturn(java.util.Optional.empty());
        Mockito.when(foodRepo.findByEnabledOrderByCategoryAscIdAsc(1)).thenReturn(sampleFoods());
        planner = new DietPlanner(foodRepo, ruleRepo);
    }

    private List<FoodItem> sampleFoods() {
        List<FoodItem> list = new ArrayList<>();
        list.add(food("米饭(熟)", "主食", 116, 2.6, 25.9, 0.3, "碗", 150, "1 碗"));
        list.add(food("全麦面包", "主食", 246, 9.0, 43.0, 3.5, "片", 35, "1 片"));
        list.add(food("鸡胸肉", "蛋白", 133, 19.4, 0.0, 5.0, "g", 100, "100g"));
        list.add(food("鸡蛋", "蛋白", 144, 13.3, 2.8, 8.8, "个", 50, "1 个"));
        list.add(food("牛奶(全脂)", "乳品", 54, 3.0, 3.4, 3.2, "杯", 250, "1 杯"));
        list.add(food("西兰花", "蔬菜", 36, 4.0, 4.3, 0.6, "份", 200, "1 份"));
        list.add(food("苹果", "水果", 53, 0.4, 13.5, 0.2, "个", 200, "1 个"));
        list.add(food("橄榄油", "油脂", 899, 0, 0, 99.9, "勺", 10, "1 勺"));
        return list;
    }

    private FoodItem food(String name, String cat, double kcal, double p, double c, double f,
                          String unit, double grams, String household) {
        FoodItem fi = FoodItem.builder()
                .name(name).category(cat).kcalPer100g(kcal)
                .proteinG(p).carbG(c).fatG(f)
                .unitLabel(unit).householdMeasure(household).gramsPerUnit(grams)
                .sourceCode("CFCT-6").sourceTitle("中国食物成分表 标准版 第6版")
                .sourceUrl("https://www.chinanutri.cn/").evidenceLevel("A")
                .verified(1).enabled(1).build();
        return fi;
    }

    @Test
    void targetKeepsMacrosConsistent() {
        DietPlanner.Target t = planner.computeTarget("fat_loss", 70, 1800);
        assertEquals(1800.0, t.kcal(), 0.1);
        // 减脂 1.8–2.2 g/kg 取中值 2.0 → 140g
        assertEquals(140.0, t.proteinG(), 0.5);
        // 脂肪 25% 热量
        assertEquals(50.0, t.fatG(), 0.5);
        // 三大营养素回算热量应贴近目标
        double kcalFromMacros = t.proteinG() * 4 + t.carbG() * 4 + t.fatG() * 9;
        assertEquals(1800.0, kcalFromMacros, 15.0);
    }

    @Test
    void sevenDaysAllMealsHaveUnitsAndCalories() {
        DietPlanner.Target t = planner.computeTarget("health", 60, 1900);
        List<DietPlanItem> items = planner.plan(t, Set.of(1, 3, 5), "health");
        assertFalse(items.isEmpty(), "饮食明细不应为空");

        Map<Integer, Double> perDay = new HashMap<>();
        Map<Integer, Double> proteinPerDay = new HashMap<>();
        Set<String> mealsOnMonday = new java.util.HashSet<>();
        for (DietPlanItem it : items) {
            perDay.merge(it.getDayOfWeek(), it.getKcal(), Double::sum);
            proteinPerDay.merge(it.getDayOfWeek(), it.getProteinG(), Double::sum);
            assertNotNull(it.getUnitLabel(), "每条食物都要有计量单位");
            assertTrue(it.getGrams() > 0, "每条食物都要有克数");
            assertNotNull(it.getAmountDesc(), "每条食物都要有可读份量描述");
            assertTrue(it.getKcal() > 0, "每条食物都要有热量");
            assertNotNull(it.getSourceUrl(), "每条食物都要能溯源");
            if (it.getDayOfWeek() == 1) {
                mealsOnMonday.add(it.getMeal());
            }
        }
        assertEquals(7, perDay.size(), "应覆盖 7 天");
        for (Map.Entry<Integer, Double> e : perDay.entrySet()) {
            assertEquals(t.kcal(), e.getValue(), t.kcal() * 0.30,
                    "第 " + e.getKey() + " 天总热量应贴近目标 ±30%（食物粒度决定）");
        }
        for (Map.Entry<Integer, Double> e : proteinPerDay.entrySet()) {
            assertTrue(e.getValue() >= t.proteinG() * 0.85,
                    "第 " + e.getKey() + " 天蛋白应达目标 85% 以上，实际 " + e.getValue());
        }
        assertTrue(mealsOnMonday.contains("加餐"), "训练日应多一餐加餐");
    }

    @Test
    void nonTrainingDayHasNoSnack() {
        DietPlanner.Target t = planner.computeTarget("health", 60, 1800);
        List<DietPlanItem> items = planner.plan(t, Set.of(1), "health");
        boolean snackOnTuesday = items.stream()
                .anyMatch(i -> i.getDayOfWeek() == 2 && "加餐".equals(i.getMeal()));
        assertFalse(snackOnTuesday, "非训练日不应有加餐");
    }

    @Test
    void emptyFoodTableDegradesToEmptyPlan() {
        Mockito.when(foodRepo.findByEnabledOrderByCategoryAscIdAsc(1)).thenReturn(List.of());
        DietPlanner.Target t = planner.computeTarget("health", 60, 1800);
        assertTrue(planner.plan(t, Set.of(1), "health").isEmpty());
    }

    @Test
    void ruleOverrideChangesProteinTarget() {
        KnowledgeRule rule = KnowledgeRule.builder()
                .code("DIET_TARGET").enabled(1).version(1)
                .expression("{\"proteinPerKg\":{\"fat_loss\":[2.2,2.6]},\"fatRatio\":0.20}")
                .build();
        Mockito.when(ruleRepo.findByCode("DIET_TARGET")).thenReturn(java.util.Optional.of(rule));
        DietPlanner custom = new DietPlanner(foodRepo, ruleRepo);
        DietPlanner.Target t = custom.computeTarget("fat_loss", 70, 1800);
        assertEquals(168.0, t.proteinG(), 0.5, "蛋白区间中值 2.4 g/kg × 70kg");
        assertEquals(40.0, t.fatG(), 0.5, "脂肪比例改为 20%");
    }
}