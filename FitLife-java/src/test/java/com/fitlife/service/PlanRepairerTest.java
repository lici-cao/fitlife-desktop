package com.fitlife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.dto.PlanDayVO;
import com.fitlife.dto.PlanItemVO;
import com.fitlife.dto.PlanVO;
import com.fitlife.dto.ValidationReport;
import com.fitlife.entity.Exercise;
import com.fitlife.repository.KnowledgeRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 最小修复器测试（P2 验收 A-6）：只动违例点，其余保持；每条修复有 action 文案。
 */
class PlanRepairerTest {

    private PlanRepairer repairer;
    private PlanValidator validator;

    @BeforeEach
    void setUp() {
        repairer = new PlanRepairer();
        KnowledgeRuleRepository ruleRepository = mock(KnowledgeRuleRepository.class);
        when(ruleRepository.findByEnabled(anyInt())).thenReturn(List.of());
        validator = new PlanValidator(ruleRepository, new ObjectMapper());
    }

    private Exercise ex(long id, String name, String category, String muscle, int difficulty, String contra) {
        return Exercise.builder().id(id).name(name).category(category).muscleGroup(muscle)
                .equipment("器械").difficulty(difficulty).metValue(6.0)
                .defaultSets(3).defaultReps("8-12").contraindication(contra).build();
    }

    private PlanItemVO item(long exId, String name, String muscle, int rpe) {
        return PlanItemVO.builder().exerciseId(exId).exerciseName(name).muscleGroup(muscle)
                .category("力量").sets(3).reps("8-12").restSec(90).rpe(rpe).seq(1).estCalories(40.0).build();
    }

    private PlanVO voWith(PlanDayVO... days) {
        List<PlanDayVO> all = new ArrayList<>(List.of(days));
        for (int d = 1; d <= 7; d++) {
            final int dow = d;
            if (all.stream().noneMatch(x -> x.getDayOfWeek() == dow)) {
                all.add(PlanDayVO.builder().dayOfWeek(dow).dayLabel("周" + dow).focus("休息")
                        .restDay(true).estMinutes(0).estCalories(0.0).items(new ArrayList<>()).build());
            }
        }
        return PlanVO.builder().title("t").goal("减脂").weeklyDays(3).sessionMinutes(45)
                .weeklyCardioMin(100).weeklyCalories(900.0).days(all).build();
    }

    private PlanDayVO trainDay(int dow, List<PlanItemVO> items) {
        return PlanDayVO.builder().dayOfWeek(dow).dayLabel("周" + dow).focus("腿")
                .restDay(false).estMinutes(40).estCalories(160.0).items(new ArrayList<>(items)).build();
    }

    @Test
    @DisplayName("V5 禁忌动作 → 同肌群安全替代，并记录修复动作")
    void replacesContraindicatedExercise() {
        Exercise squat = ex(1L, "杠铃深蹲", "力量", "腿", 2, "膝关节");
        Exercise press = ex(2L, "器械腿举", "力量", "腿", 2, null);
        Map<Long, Exercise> candidates = Map.of(1L, squat, 2L, press);

        PlanVO vo = voWith(trainDay(5, List.of(item(1L, "杠铃深蹲", "腿", 6))));
        PlanValidator.VContext ctx = new PlanValidator.VContext(30, null, 2, "减脂", 3, 45,
                List.of("膝关节"), 70.0);

        ValidationReport report = validator.validate(vo, candidates, ctx, null);
        assertFalse(report.isPassed(), "先要能检出 V5 ERROR");

        List<String> actions = repairer.repair(vo, candidates, report, ctx);

        assertEquals(1, actions.size(), "只应有一条替换动作：" + actions);
        assertTrue(actions.get(0).contains("禁忌替换"));
        assertEquals(2L, vo.getDays().get(0).getItems().get(0).getExerciseId(), "深蹲应被替换为腿举");
    }

    @Test
    @DisplayName("V1 越界 RPE 压回上限（高级 cap=8），其余动作不动")
    void clampsRpeOnlyOnViolatingItems() {
        Exercise a = ex(1L, "卧推", "力量", "胸", 2, null);
        Exercise b = ex(2L, "飞鸟", "力量", "胸", 1, null);
        Map<Long, Exercise> candidates = Map.of(1L, a, 2L, b);

        PlanVO vo = voWith(trainDay(1, List.of(item(1L, "卧推", "胸", 10), item(2L, "飞鸟", "胸", 6))));
        PlanValidator.VContext ctx = new PlanValidator.VContext(30, null, 2, "减脂", 3, 45, List.of(), 70.0);

        ValidationReport report = validator.validate(vo, candidates, ctx, null);
        List<String> actions = repairer.repair(vo, candidates, report, ctx);

        List<PlanItemVO> items = vo.getDays().get(0).getItems();
        assertEquals(8, items.get(0).getRpe(), "RPE 10 → cap 8");
        assertEquals(6, items.get(1).getRpe(), "合规动作不许动");
        assertTrue(actions.stream().anyMatch(s -> s.contains("RPE 10→8")));
    }

    @Test
    @DisplayName("修复后重新校验：V5 违例消除（≤2 轮收敛的前提）")
    void repairedPlanPassesSecondValidation() {
        Exercise squat = ex(1L, "杠铃深蹲", "力量", "腿", 2, "膝关节");
        Exercise press = ex(2L, "器械腿举", "力量", "腿", 2, null);
        Map<Long, Exercise> candidates = Map.of(1L, squat, 2L, press);

        PlanVO vo = voWith(trainDay(5, List.of(item(1L, "杠铃深蹲", "腿", 6))));
        PlanValidator.VContext ctx = new PlanValidator.VContext(30, null, 2, "减脂", 3, 45,
                List.of("膝关节"), 70.0);

        ValidationReport first = validator.validate(vo, candidates, ctx, null);
        repairer.repair(vo, candidates, first, ctx);
        ValidationReport second = validator.validate(vo, candidates, ctx, null);

        assertTrue(second.getChecks().stream().noneMatch(c -> c.getCode().equals("V5")),
                "第二轮不应再有 V5：" + second.getChecks());
    }

    @Test
    @DisplayName("V6 超量：删尾部动作并重排 seq 到 6 个以内")
    void trimsCapacityOverflow() {
        Map<Long, Exercise> candidates = new java.util.HashMap<>();
        List<PlanItemVO> items = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            long id = 10 + i;
            candidates.put(id, ex(id, "动作" + i, "力量", "胸", 1, null));
            items.add(item(id, "动作" + i, "胸", 6));
        }
        PlanVO vo = voWith(trainDay(1, items));
        PlanValidator.VContext ctx = new PlanValidator.VContext(30, null, 2, "减脂", 3, 45, List.of(), 70.0);

        ValidationReport report = validator.validate(vo, candidates, ctx, null);
        repairer.repair(vo, candidates, report, ctx);

        List<PlanItemVO> kept = vo.getDays().get(0).getItems();
        assertEquals(6, kept.size(), "应删到 6 个");
        for (int i = 0; i < kept.size(); i++) {
            assertEquals(i + 1, kept.get(i).getSeq(), "seq 重排");
        }
    }
}
