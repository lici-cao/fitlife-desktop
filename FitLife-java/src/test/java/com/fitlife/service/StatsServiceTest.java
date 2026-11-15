package com.fitlife.service;

import com.fitlife.dto.StatsSummaryVO;
import com.fitlife.entity.PlanAdherence;
import com.fitlife.entity.UserProfile;
import com.fitlife.entity.WorkoutRecord;
import com.fitlife.repository.KnowledgeRuleRepository;
import com.fitlife.repository.PlanAdherenceRepository;
import com.fitlife.repository.UserProfileRepository;
import com.fitlife.repository.WorkoutRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统计口径单测：依从率、训练量、热量收支、RPE 偏差与风险分级。
 * 语义要求：同一份数据无论前端怎么画，服务端口径唯一。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatsServiceTest {

    @Mock private PlanAdherenceRepository planAdherenceRepository;
    @Mock private WorkoutRecordRepository workoutRecordRepository;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private KnowledgeRuleRepository knowledgeRuleRepository;
    @Mock private DailySummaryService dailySummaryService;

    private StatsService service;

    private final LocalDate end = LocalDate.of(2026, 11, 8); // 周日

    @BeforeEach
    void setUp() {
        service = new StatsService(planAdherenceRepository, workoutRecordRepository,
                userProfileRepository, knowledgeRuleRepository, dailySummaryService,
                new com.fasterxml.jackson.databind.ObjectMapper());
        when(knowledgeRuleRepository.findByCode(any())).thenReturn(Optional.empty());
        when(knowledgeRuleRepository.findByEnabled(any())).thenReturn(List.of());
        when(dailySummaryService.resolvePlanFor(anyLong(), any())).thenReturn(Optional.empty());
        when(dailySummaryService.plannedAndDone(anyLong(), any())).thenReturn(new int[] { 0, 0 });
        when(planAdherenceRepository.findByUserIdAndDateBetweenOrderByDateAsc(anyLong(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("依从率 = 完成/应做，训练量与热量收支按明细求和")
    void aggregatesAdherenceAndVolume() {
        // 7 天窗口内 3 个训练日：应做 9、完成 6、跳过 1
        when(planAdherenceRepository.findByUserIdAndDateBetweenOrderByDateAsc(anyLong(), any(), any()))
                .thenReturn(List.of(
                        adherence(end.minusDays(6), 3, 3, 0, 90, 500.0, 7.0, 0),
                        adherence(end.minusDays(5), 3, 2, 1, 60, 300.0, 8.0, 0),
                        adherence(end.minusDays(3), 3, 1, 0, 30, 150.0, 6.0, 0)));
        when(dailySummaryService.intakeByDate(anyLong(), any(), any()))
                .thenReturn(Map.of(end.minusDays(6), 1800.0, end.minusDays(5), 2000.0));
        when(dailySummaryService.workoutsByDate(anyLong(), any(), any())).thenReturn(Map.of(
                end.minusDays(6), List.of(workout(end.minusDays(6), 90, 500.0, 7)),
                end.minusDays(5), List.of(workout(end.minusDays(5), 60, 300.0, 8)),
                end.minusDays(3), List.of(workout(end.minusDays(3), 30, 150.0, 6))));

        StatsSummaryVO vo = service.summary(1L, end.toString(), 7);

        assertThat(vo.getPlannedItems()).isEqualTo(9);
        assertThat(vo.getDoneItems()).isEqualTo(6);
        assertThat(vo.getSkippedItems()).isEqualTo(1);
        assertThat(vo.getAdherenceRate()).isEqualTo(67); // 66.67 → 67
        assertThat(vo.getTotalMinutes()).isEqualTo(180);
        assertThat(vo.getTotalSessions()).isEqualTo(3);
        assertThat(vo.getTotalBurnCalories()).isEqualTo(950.0);
        assertThat(vo.getTotalIntakeCalories()).isEqualTo(3800.0);
        assertThat(vo.getNetCalories()).isEqualTo(2850.0);
        assertThat(vo.getAvgWeeklyMinutes()).isEqualTo(180); // 180×7/7
        assertThat(vo.getSeries()).hasSize(7);
        assertThat(vo.getSeries().get(6).getDate()).isEqualTo(end.toString());
    }

    @Test
    @DisplayName("RPE 偏差 = 实际均值 − 计划均值；无实际数据为 null")
    void rpeDeviation() {
        when(planAdherenceRepository.findByUserIdAndDateBetweenOrderByDateAsc(anyLong(), any(), any()))
                .thenReturn(List.of(adherence(end, 4, 4, 0, 120, 600.0, 8.0, 0)));
        when(dailySummaryService.workoutsByDate(anyLong(), any(), any())).thenReturn(Map.of(
                end, List.of(workout(end, 60, 300.0, 8), workout(end, 60, 300.0, 8))));
        when(dailySummaryService.intakeByDate(anyLong(), any(), any())).thenReturn(Map.of());

        StatsSummaryVO vo = service.summary(1L, end.toString(), 7);
        // 计划均值取自当前计划；此处无计划 → 偏差为 null
        assertThat(vo.getRpeDeviation()).isNull();
        assertThat(vo.getAvgActualRpe()).isEqualTo(8.0);
    }

    @Test
    @DisplayName("疼痛上报 → DANGER；依从率低于下限 → DANGER；依从率中等 → INFO")
    void riskLevels() {
        List<StatsSummaryVO.RiskNote> pain = service.buildRisks(50, null, 2, 10, 5, 7, end);
        assertThat(pain).anyMatch(n -> "DANGER".equals(n.getLevel()) && "PAIN_REPORTED".equals(n.getCode()));

        List<StatsSummaryVO.RiskNote> low = service.buildRisks(40, null, 0, 10, 4, 7, end);
        assertThat(low).anyMatch(n -> "DANGER".equals(n.getLevel()) && "ADHERENCE_LOW".equals(n.getCode()));

        List<StatsSummaryVO.RiskNote> mid = service.buildRisks(80, null, 0, 10, 8, 7, end);
        assertThat(mid).anyMatch(n -> "INFO".equals(n.getLevel()) && "ADHERENCE_MEDIUM".equals(n.getCode()));
        assertThat(mid).noneMatch(n -> "DANGER".equals(n.getLevel()));
    }

    @Test
    @DisplayName("RPE 高于计划 → WARN(RPE_HIGH)；低于计划 → WARN(RPE_LOW)")
    void rpeRisks() {
        assertThat(service.buildRisks(95, 1.6, 0, 12, 12, 14, end))
                .anyMatch(n -> "WARN".equals(n.getLevel()) && "RPE_HIGH".equals(n.getCode()));
        assertThat(service.buildRisks(95, -2.0, 0, 12, 12, 14, end))
                .anyMatch(n -> "WARN".equals(n.getLevel()) && "RPE_LOW".equals(n.getCode()));
    }

    @Test
    @DisplayName("连续达标：休息日不打断，未达标即中断")
    void streakSkipsRestDays() {
        when(planAdherenceRepository.findByUserIdAndDateBetweenOrderByDateAsc(anyLong(), any(), any()))
                .thenReturn(List.of(
                        adherence(end, 3, 3, 0, 60, 300.0, 7.0, 0),
                        adherence(end.minusDays(2), 3, 3, 0, 60, 300.0, 7.0, 0),
                        adherence(end.minusDays(4), 3, 1, 2, 20, 100.0, 7.0, 0)));
        assertThat(service.streakDays(1L, end)).isEqualTo(2);
    }

    @Test
    @DisplayName("无计划数据时给 INFO 提示且不写库")
    void noPlanGivesHint() {
        when(dailySummaryService.workoutsByDate(anyLong(), any(), any())).thenReturn(Map.of());
        when(dailySummaryService.intakeByDate(anyLong(), any(), any())).thenReturn(Map.of());

        StatsSummaryVO vo = service.summary(1L, end.toString(), 7);

        assertThat(vo.getPlannedItems()).isZero();
        assertThat(vo.getAdherenceRate()).isZero();
        assertThat(vo.getRisks()).anyMatch(n -> "NO_PLAN_DATA".equals(n.getCode()));
        verify(planAdherenceRepository, never()).save(any());
    }

    // ---------- helpers ----------

    private PlanAdherence adherence(LocalDate d, int planned, int done, int skipped,
                                    int minutes, double calories, double avgRpe, int pain) {
        return PlanAdherence.builder().userId(1L).planId(7L).date(d)
                .plannedItems(planned).doneItems(done).skippedItems(skipped)
                .minutes(minutes).calories(calories).avgRpe(avgRpe).painFlag(pain).build();
    }

    private WorkoutRecord workout(LocalDate d, int minutes, double calories, int rpe) {
        return WorkoutRecord.builder().userId(1L).date(d).planDetailId(1L).status("DONE")
                .duration(minutes).caloriesBurned(calories).rpe(rpe).painFlag(0).build();
    }
}