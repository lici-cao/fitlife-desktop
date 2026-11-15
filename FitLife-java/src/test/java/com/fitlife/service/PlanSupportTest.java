package com.fitlife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.dto.PlanVO;
import com.fitlife.entity.Exercise;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PlanSupport 纯函数测试：解析严格校验、估算公式、模板兜底结构完整性。
 */
class PlanSupportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Exercise ex(long id, String name, String category, String muscle, double met) {
        return Exercise.builder().id(id).name(name).category(category).muscleGroup(muscle)
                .equipment("器械").difficulty(2).metValue(met)
                .defaultSets(3).defaultReps("8-12").build();
    }

    @Test
    @DisplayName("parseAiPlan：合法 JSON 全解析，幻觉动作 id 被丢弃")
    void parsesValidPlanAndDropsHallucination() {
        List<Exercise> candidates = List.of(
                ex(1L, "卧推", "力量", "胸", 6.0),
                ex(2L, "跑步", "有氧", "腿", 8.0));
        String raw = """
                好的，这是你的计划：
                ```json
                {"title":"我的计划","summary":"三练","weeklyCardioMin":150,
                 "days":[
                   {"dayOfWeek":1,"focus":"胸","items":[{"exerciseId":1,"sets":4,"reps":"8","restSec":90,"rpe":7,"seq":1},
                     {"exerciseId":999,"sets":3,"reps":"10","restSec":60,"rpe":6,"seq":2}]},
                   {"dayOfWeek":3,"focus":"有氧","items":[{"exerciseId":2,"sets":1,"reps":"30分钟","restSec":0,"rpe":5,"seq":1}]}
                 ]}
                ```
                """;

        PlanVO vo = PlanSupport.parseAiPlan(mapper, raw, candidates, 2, 45, 70.0);

        assertNotNull(vo);
        assertEquals("我的计划", vo.getTitle());
        assertEquals(7, vo.getDays().size(), "必须补齐 7 天");
        var d1 = vo.getDays().get(0);
        assertEquals(1, d1.getItems().size(), "id=999 幻觉动作应被丢弃");
        assertEquals(4, d1.getItems().get(0).getSets());
        assertFalse(vo.getDays().get(2).getRestDay());
        assertTrue(vo.getDays().get(2).getItems().get(0).getEstCalories() > 0);
    }

    @Test
    @DisplayName("parseAiPlan：训练日偏差 >1 天整份作废")
    void rejectsWrongTrainingDayCount() {
        List<Exercise> candidates = List.of(ex(1L, "卧推", "力量", "胸", 6.0));
        String raw = """
                {"days":[{"dayOfWeek":1,"focus":"胸","items":[{"exerciseId":1,"sets":3,"reps":"8","restSec":60,"rpe":6,"seq":1}]}]}
                """;
        assertNull(PlanSupport.parseAiPlan(mapper, raw, candidates, 5, 45, 70.0));
    }

    @Test
    @DisplayName("parseAiPlan：非 JSON / 空 days 返回 null 触发降级")
    void rejectsGarbage() {
        List<Exercise> candidates = List.of(ex(1L, "卧推", "力量", "胸", 6.0));
        assertNull(PlanSupport.parseAiPlan(mapper, "抱歉我做不了", candidates, 3, 45, 70.0));
        assertNull(PlanSupport.parseAiPlan(mapper, "{\"days\":[]}", candidates, 3, 45, 70.0));
    }

    @Test
    @DisplayName("estimateCalories：有氧按 reps 分钟数（MET×kg×h×1.05）")
    void cardioCaloriesFormula() {
        Exercise run = ex(1L, "跑步", "有氧", "腿", 8.0);
        // 8 × 70 × (20/60) × 1.05 = 196.0
        assertEquals(196.0, PlanSupport.estimateCalories(run, 1, "20分钟", 70.0), 0.01);
    }

    @Test
    @DisplayName("buildTemplatePlan：3 天减脂 → 7 天结构 + 3 训练日 + 追加有氧")
    void templatePlanStructure() {
        List<Exercise> candidates = new ArrayList<>();
        long id = 1;
        for (String muscle : List.of("胸", "背", "肩", "手臂", "腿", "臀", "核心")) {
            candidates.add(ex(id++, "力量-" + muscle, "力量", muscle, 6.0));
            candidates.add(ex(id++, "核心-" + muscle, "核心", "核心", 5.0));
        }
        candidates.add(ex(id++, "跑步", "有氧", "腿", 8.0));

        PlanVO vo = PlanSupport.buildTemplatePlan("减脂", 1, 3, 45, candidates, 70.0);

        assertNotNull(vo);
        assertEquals(7, vo.getDays().size());
        long trainDays = vo.getDays().stream().filter(d -> !d.getRestDay()).count();
        assertEquals(3, trainDays);
        assertTrue(vo.getWeeklyCardioMin() != null && vo.getWeeklyCardioMin() > 0, "减脂模板应带有氧");
        vo.getDays().forEach(d -> {
            if (!d.getRestDay()) {
                assertTrue(d.getItems().size() >= 4 && d.getItems().size() <= 6,
                        "单次动作数在 4-6：" + d.getItems().size());
            }
        });
    }

    @Test
    @DisplayName("parseCsv：分隔符兼容与器械白名单")
    void csvParsing() {
        assertEquals(Set.of("哑铃", "弹力带"),
                PlanSupport.parseCsv("哑铃，弹力带; 未知器械", PlanSupport.EQUIPMENT_WHITELIST));
        assertEquals(Set.of("徒手"),
                PlanSupport.parseCsv("不存在的器械", PlanSupport.EQUIPMENT_WHITELIST), "全非法退回徒手");
        assertEquals(Set.of("随便"), PlanSupport.parseCsv("随便", null));
    }

    @Test
    @DisplayName("experienceToLevel / isCutGoal / clamp 边界")
    void smallHelpers() {
        assertEquals(1, PlanSupport.experienceToLevel("新手"));
        assertEquals(2, PlanSupport.experienceToLevel("中级"));
        assertEquals(3, PlanSupport.experienceToLevel("高级"));
        assertTrue(PlanSupport.isCutGoal("fat_loss"));
        assertTrue(PlanSupport.isCutGoal("塑形"));
        assertFalse(PlanSupport.isCutGoal("增肌"));
        assertEquals(7, PlanSupport.clamp(99, 1, 7), "越上限取 max");
        assertEquals(1, PlanSupport.clamp(null, 1, 7), "null 取 min");
    }

    @Test
    @DisplayName("hitContraindication：伤病命中禁忌逗号串")
    void contraindicationMatch() {
        Exercise squat = Exercise.builder().id(1L).name("深蹲").contraindication("膝关节,腰椎间盘").build();
        assertTrue(PlanSupport.hitContraindication(squat, Set.of("膝关节")));
        assertFalse(PlanSupport.hitContraindication(squat, Set.of("腕关节")));
        assertFalse(PlanSupport.hitContraindication(squat, Set.of()));
    }
}
