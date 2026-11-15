package com.fitlife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.dto.PlanDayVO;
import com.fitlife.dto.PlanItemVO;
import com.fitlife.dto.PlanVO;
import com.fitlife.dto.ValidationReport;
import com.fitlife.entity.Exercise;
import com.fitlife.entity.KnowledgeRule;
import com.fitlife.repository.KnowledgeRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 规则校验器六类校验的纯逻辑测试（P2 验收 A-5：mvn test 真跑）。
 * 规则表用空 Mock → 全部走内置默认阈值，与 V2 迁移种子一致。
 */
class PlanValidatorTest {

    private KnowledgeRuleRepository ruleRepository;
    private PlanValidator validator;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(KnowledgeRuleRepository.class);
        when(ruleRepository.findByEnabled(anyInt())).thenReturn(List.of());
        validator = new PlanValidator(ruleRepository, new ObjectMapper());
    }

    // ---- 构造工具 ----

    private PlanItemVO item(long exId, String name, String muscle, String category, int sets, int rpe) {
        return PlanItemVO.builder()
                .exerciseId(exId).exerciseName(name).muscleGroup(muscle).category(category)
                .sets(sets).reps("8-12").restSec(90).rpe(rpe).seq(1).estCalories(50.0)
                .build();
    }

    /** n 个动作、同肌群、指定 RPE 的一天 */
    private PlanDayVO day(int dow, String muscle, int items, int rpe, String category) {
        List<PlanItemVO> list = new ArrayList<>();
        for (int i = 0; i < items; i++) {
            list.add(item(100 + dow * 10 + i, muscle + "训练" + (i + 1), muscle, category, 3, rpe));
        }
        return PlanDayVO.builder()
                .dayOfWeek(dow).dayLabel("周" + dow).focus(muscle).restDay(false)
                .estMinutes(45).estCalories(200.0).items(list).build();
    }

    private PlanVO plan(PlanDayVO... days) {
        List<PlanDayVO> all = new ArrayList<>(List.of(days));
        for (int d = 1; d <= 7; d++) {
            final int dow = d;
            if (all.stream().noneMatch(x -> x.getDayOfWeek() == dow)) {
                all.add(PlanDayVO.builder().dayOfWeek(dow).dayLabel("周" + dow).focus("休息")
                        .restDay(true).estMinutes(0).estCalories(0.0).items(new ArrayList<>()).build());
            }
        }
        return PlanVO.builder().title("测试计划").goal("减脂").weeklyDays(3).sessionMinutes(45)
                .weeklyCardioMin(160).weeklyCalories(1500.0).days(all).build();
    }

    private PlanValidator.VContext ctx(List<String> injuries) {
        return new PlanValidator.VContext(30, null, 2, "减脂", 3, 45, injuries, 70.0);
    }

    // ---- 用例 ----

    @Test
    @DisplayName("干净计划：六类全过，score=100，仍带风险提示")
    void cleanPlanPasses() {
        PlanVO vo = plan(day(1, "胸", 5, 6, "力量"), day(3, "背", 5, 6, "力量"), day(5, "腿", 5, 6, "力量"));
        ValidationReport r = validator.validate(vo, Map.of(), ctx(List.of()), null);

        assertTrue(r.isPassed());
        assertEquals(100, r.getScore(), "无违例应满分：" + r.getChecks());
        assertTrue(r.getChecks().isEmpty());
        assertFalse(r.getRiskNotes().isEmpty(), "通过也要给靶心率提示");
    }

    @Test
    @DisplayName("V1：RPE=10 超 90%HRR → ERROR")
    void v1TargetHeartRateError() {
        PlanVO vo = plan(day(1, "胸", 5, 10, "力量"), day(3, "背", 5, 6, "力量"), day(5, "腿", 5, 6, "力量"));
        ValidationReport r = validator.validate(vo, Map.of(), ctx(List.of()), null);

        assertFalse(r.isPassed());
        assertTrue(r.getChecks().stream().anyMatch(c ->
                c.getCode().equals("V1") && c.getLevel().equals("ERROR") && c.getDetail().contains("bpm")));
    }

    @Test
    @DisplayName("V1：初级用户 RPE≥8 → WARN 不致命")
    void v1BeginnerWarn() {
        PlanVO vo = plan(day(1, "胸", 5, 8, "力量"), day(3, "背", 5, 6, "力量"), day(5, "腿", 5, 6, "力量"));
        PlanValidator.VContext beginner = new PlanValidator.VContext(30, null, 1, "减脂", 3, 45, List.of(), 70.0);
        ValidationReport r = validator.validate(vo, Map.of(), beginner, null);

        assertTrue(r.isPassed(), "WARN 不影响 passed");
        assertEquals(95, r.getScore(), "一个 WARN 扣 5 分");
        assertTrue(r.getChecks().stream().anyMatch(c -> c.getCode().equals("V1") && c.getLevel().equals("WARN")));
    }

    @Test
    @DisplayName("V3：周总组数环比 +25% → ERROR（循序渐进 ≤10%）")
    void v3ProgressionError() {
        PlanVO vo = plan(day(1, "胸", 5, 6, "力量"), day(3, "背", 5, 6, "力量"), day(5, "腿", 5, 6, "力量"));
        // 当前周 45 组（3 天 × 5 动作 × 3 组），上一版 36 组 → +25%
        ValidationReport r = validator.validate(vo, Map.of(), ctx(List.of()), Map.of("胸", 12, "背", 12, "腿", 12));

        assertFalse(r.isPassed());
        assertTrue(r.getChecks().stream().anyMatch(c ->
                c.getCode().equals("V3") && c.getLevel().equals("ERROR") && c.getDetail().contains("25%")));
    }

    @Test
    @DisplayName("V4：同肌群连日高强度 → ERROR")
    void v4RecoveryError() {
        PlanVO vo = plan(day(1, "胸", 5, 8, "力量"), day(2, "胸", 5, 8, "力量"), day(5, "腿", 5, 6, "力量"));
        ValidationReport r = validator.validate(vo, Map.of(), ctx(List.of()), null);

        assertFalse(r.isPassed());
        assertTrue(r.getChecks().stream().anyMatch(c ->
                c.getCode().equals("V4") && c.getLevel().equals("ERROR") && c.getDetail().contains("胸")));
    }

    @Test
    @DisplayName("V5：伤病命中禁忌字段 → ERROR")
    void v5ContraindicationError() {
        Exercise squat = Exercise.builder().id(1L).name("杠铃深蹲").category("力量")
                .muscleGroup("腿").difficulty(2).contraindication("膝关节,腰椎").build();
        PlanVO vo = plan(day(1, "胸", 5, 6, "力量"), day(3, "背", 5, 6, "力量"), day(5, "腿", 5, 6, "力量"));
        // 把周5第一个动作换成深蹲（训练日在列表前部，按 dow 查找）
        vo.getDays().stream().filter(x -> x.getDayOfWeek() == 5).findFirst().orElseThrow()
                .getItems().set(0, item(1L, "杠铃深蹲", "腿", "力量", 3, 6));

        Map<Long, Exercise> candidates = Map.of(1L, squat);
        ValidationReport r = validator.validate(vo, candidates, ctx(List.of("膝关节")), null);

        assertFalse(r.isPassed());
        assertTrue(r.getChecks().stream().anyMatch(c ->
                c.getCode().equals("V5") && c.getLevel().equals("ERROR") && c.getDetail().contains("杠铃深蹲")));
    }

    @Test
    @DisplayName("V6：单次 8 个动作 → WARN；周组数超 20 → WARN")
    void v6CapacityWarn() {
        PlanVO vo = plan(day(1, "胸", 8, 6, "力量"), day(3, "背", 5, 6, "力量"), day(5, "腿", 5, 6, "力量"));
        ValidationReport r = validator.validate(vo, Map.of(), ctx(List.of()), null);

        assertTrue(r.isPassed(), "容量问题只 WARN");
        assertTrue(r.getChecks().stream().anyMatch(c ->
                c.getCode().equals("V6") && c.getDetail().contains("建议 4-6 个")));
        assertTrue(r.getChecks().stream().anyMatch(c ->
                c.getCode().equals("V6") && c.getDetail().contains("周组数")));
    }

    @Test
    @DisplayName("knowledge_rule 阈值生效：maxPct=0.70 时 RPE8 即 ERROR")
    void ruleTableOverridesDefaults() {
        KnowledgeRule hr = KnowledgeRule.builder()
                .code("HR_TARGET").name("靶心率").category("HR")
                .expression("{\"maxPct\":0.70}").enabled(1).version(1).build();
        when(ruleRepository.findByEnabled(anyInt())).thenReturn(List.of(hr));

        PlanVO vo = plan(day(1, "胸", 5, 8, "力量"), day(3, "背", 5, 6, "力量"), day(5, "腿", 5, 6, "力量"));
        ValidationReport r = validator.validate(vo, Map.of(), ctx(List.of()), null);

        assertFalse(r.isPassed(), "阈值 0.70 下 RPE=8（0.80）应违例");
        assertEquals(0.70, ((Number) r.getParamsUsed().get("maxPct")).doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("规则表 JSON 损坏：退回内置默认，不抛异常")
    void brokenRuleJsonFallsBack() {
        KnowledgeRule bad = KnowledgeRule.builder()
                .code("HR_TARGET").expression("这不是JSON").enabled(1).build();
        when(ruleRepository.findByEnabled(anyInt())).thenReturn(List.of(bad));

        PlanVO vo = plan(day(1, "胸", 5, 6, "力量"), day(3, "背", 5, 6, "力量"), day(5, "腿", 5, 6, "力量"));
        ValidationReport r = validator.validate(vo, Map.of(), ctx(List.of()), null);

        assertTrue(r.isPassed());
    }

    @Test
    @DisplayName("weeklySets 静态汇总正确（供 V3 环比基线）")
    void weeklySetsAggregates() {
        PlanVO vo = plan(day(1, "胸", 4, 6, "力量"), day(3, "胸", 3, 6, "力量"));
        Map<String, Integer> sets = new HashMap<>(PlanValidator.weeklySets(vo));
        assertEquals(21, sets.get("胸"), "4天×3 + 3天×3 = 21 组");
    }
}
