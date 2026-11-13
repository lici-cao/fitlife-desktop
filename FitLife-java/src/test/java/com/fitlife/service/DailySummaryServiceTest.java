package com.fitlife.service;

import com.fitlife.entity.DailyRecord;
import com.fitlife.entity.FoodIntake;
import com.fitlife.entity.Plan;
import com.fitlife.entity.PlanAdherence;
import com.fitlife.entity.PlanDetail;
import com.fitlife.entity.WorkoutRecord;
import com.fitlife.repository.DailyRecordRepository;
import com.fitlife.repository.FoodIntakeRepository;
import com.fitlife.repository.PlanAdherenceRepository;
import com.fitlife.repository.PlanDetailRepository;
import com.fitlife.repository.PlanRepository;
import com.fitlife.repository.WorkoutRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 日汇总引擎单测：验证"打卡 → 聚合"的口径与幂等。
 * 基准日 2026-11-08 是周日（dayOfWeek=7），计划明细里放了 3 个周日动作 + 1 个周一动作。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DailySummaryServiceTest {

    @Mock private DailyRecordRepository dailyRecordRepository;
    @Mock private FoodIntakeRepository foodIntakeRepository;
    @Mock private WorkoutRecordRepository workoutRecordRepository;
    @Mock private PlanRepository planRepository;
    @Mock private PlanDetailRepository planDetailRepository;
    @Mock private PlanAdherenceRepository planAdherenceRepository;

    @InjectMocks private DailySummaryService service;

    private final LocalDate date = LocalDate.of(2026, 11, 8); // 周日 = 7
    private Plan plan;

    @BeforeEach
    void setUp() {
        plan = Plan.builder().id(7L).userId(1L).status("ACTIVE").version(1)
                .createdAt(LocalDateTime.of(2026, 11, 1, 10, 0)).build();
        when(planRepository.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(plan));
        when(planRepository.findByUserIdOrderByIdAsc(1L)).thenReturn(List.of(plan));
        when(planRepository.findFirstByUserIdAndStatusOrderByIdDesc(1L, "ACTIVE"))
                .thenReturn(Optional.of(plan));
        when(planDetailRepository.findByPlanIdOrderByDayOfWeekAscSeqAsc(7L))
                .thenReturn(List.of(detail(101L, 7), detail(102L, 7), detail(103L, 7), detail(104L, 1)));
    }

    @Test
    @DisplayName("饮食与训练按当日明细求和，净热量=摄入−消耗")
    void aggregateIntakeAndBurn() {
        DailyRecord record = DailyRecord.builder().id(1L).userId(1L).date(date)
                .foodIntake(0.0).exerciseBurn(0.0).targetCalories(1800.0).weight(0.0).build();
        when(dailyRecordRepository.findByUserIdAndDate(1L, date)).thenReturn(Optional.of(record));
        when(foodIntakeRepository.findByUserIdAndDate(1L, date)).thenReturn(List.of(
                FoodIntake.builder().calories(600.0).build(),
                FoodIntake.builder().calories(450.5).build()));
        when(workoutRecordRepository.findByUserIdAndDate(1L, date)).thenReturn(List.of(
                done(101L, 40, 220.0, 7, 0),
                done(102L, 20, 130.0, 6, 0)));
        when(planAdherenceRepository.findByUserIdAndPlanIdAndDate(1L, 7L, date)).thenReturn(Optional.empty());
        when(planAdherenceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(dailyRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        DailyRecord result = service.refreshDay(1L, date);

        assertThat(result.getFoodIntake()).isEqualTo(1050.5);
        assertThat(result.getExerciseBurn()).isEqualTo(350.0);
        assertThat(result.getNetCalories()).isEqualTo(700.5);
        assertThat(result.getWorkoutMinutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("应做数取当天明细；完成数不超过应做数")
    void plannedItemsFromPlanForThatDay() {
        DailyRecord record = daily(0.0, 0.0);
        when(dailyRecordRepository.findByUserIdAndDate(1L, date)).thenReturn(Optional.of(record));
        when(foodIntakeRepository.findByUserIdAndDate(1L, date)).thenReturn(List.of());
        // 4 条完成记录，其中 999 不在计划明细中
        when(workoutRecordRepository.findByUserIdAndDate(1L, date)).thenReturn(List.of(
                done(101L, 30, 150.0, 7, 0),
                done(102L, 30, 150.0, 7, 0),
                done(103L, 30, 150.0, 7, 0),
                done(999L, 30, 150.0, 7, 0)));
        when(planAdherenceRepository.findByUserIdAndPlanIdAndDate(1L, 7L, date))
                .thenReturn(Optional.of(PlanAdherence.builder().id(5L).userId(1L).planId(7L).date(date).build()));
        when(planAdherenceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(dailyRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        DailyRecord result = service.refreshDay(1L, date);

        assertThat(result.getPlannedItems()).isEqualTo(3);
        assertThat(result.getCompletedItems()).isEqualTo(3);
    }

    @Test
    @DisplayName("依从率快照按 (用户,计划,日期) 唯一行写入")
    void writesAdherenceSnapshot() {
        DailyRecord record = daily(0.0, 0.0);
        when(dailyRecordRepository.findByUserIdAndDate(1L, date)).thenReturn(Optional.of(record));
        when(foodIntakeRepository.findByUserIdAndDate(1L, date)).thenReturn(List.of());
        when(workoutRecordRepository.findByUserIdAndDate(1L, date)).thenReturn(List.of(
                done(101L, 40, 200.0, 8, 0),
                done(102L, 25, 120.0, 7, 1),
                skipped(103L)));
        when(planAdherenceRepository.findByUserIdAndPlanIdAndDate(1L, 7L, date))
                .thenReturn(Optional.of(PlanAdherence.builder().id(9L).userId(1L).planId(7L).date(date).build()));
        ArgumentCaptor<PlanAdherence> captor = ArgumentCaptor.forClass(PlanAdherence.class);
        when(planAdherenceRepository.save(captor.capture())).thenAnswer(i -> i.getArgument(0));
        when(dailyRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        DailyRecord result = service.refreshDay(1L, date);

        PlanAdherence a = captor.getValue();
        assertThat(a.getPlanId()).isEqualTo(7L);
        assertThat(a.getPlannedItems()).isEqualTo(3);
        assertThat(a.getDoneItems()).isEqualTo(2);
        assertThat(a.getSkippedItems()).isEqualTo(1);
        assertThat(a.getMinutes()).isEqualTo(65);
        assertThat(a.getAvgRpe()).isEqualTo(7.5);
        assertThat(a.getPainFlag()).isEqualTo(1);
        assertThat(result.getPainFlag()).isEqualTo(1);
    }

    @Test
    @DisplayName("没有计划时只算饮食与运动，不写依从率快照")
    void noPlanNoAdherence() {
        when(planRepository.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of());
        when(planRepository.findByUserIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(planRepository.findFirstByUserIdAndStatusOrderByIdDesc(1L, "ACTIVE")).thenReturn(Optional.empty());
        DailyRecord record = daily(300.0, 0.0);
        when(dailyRecordRepository.findByUserIdAndDate(1L, date)).thenReturn(Optional.of(record));
        when(foodIntakeRepository.findByUserIdAndDate(1L, date)).thenReturn(List.of());
        when(workoutRecordRepository.findByUserIdAndDate(1L, date)).thenReturn(List.of(done(101L, 30, 150.0, 7, 0)));
        when(dailyRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        DailyRecord result = service.refreshDay(1L, date);

        assertThat(result.getPlannedItems()).isZero();
        assertThat(result.getExerciseBurn()).isEqualTo(150.0);
        verify(planAdherenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("历史日期回退到当天之前最近的一版计划；早于全部版本时判定为无计划")
    void resolvePlanFallsBackToHistoricalVersion() {
        Plan old = Plan.builder().id(3L).userId(1L).status("ARCHIVED").version(1)
                .createdAt(LocalDateTime.of(2026, 10, 1, 9, 0)).build();
        Plan nov = Plan.builder().id(7L).userId(1L).status("ACTIVE").version(2)
                .createdAt(LocalDateTime.of(2026, 11, 1, 9, 0)).build();
        when(planRepository.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(nov, old));
        when(planRepository.findFirstByUserIdAndStatusOrderByIdDesc(1L, "ACTIVE")).thenReturn(Optional.of(nov));
        when(planRepository.findByUserIdOrderByIdAsc(1L)).thenReturn(List.of(old, nov));

        // 10-20 落在 old 创建之后、nov 之前：回退到 old（历史口径）
        assertThat(service.resolvePlanFor(1L, LocalDate.of(2026, 10, 20))).contains(old);
        // 11-09：取最新一版
        assertThat(service.resolvePlanFor(1L, LocalDate.of(2026, 11, 9))).contains(nov);
        // 9-20 早于全部版本：当天还没有计划，不得借未来版本当分母
        assertThat(service.resolvePlanFor(1L, LocalDate.of(2026, 9, 20))).isEmpty();
    }

    @Test
    @DisplayName("补录未执行日：只补有计划且未落快照的过去日期")
    void ensureMissedDaysCreatesZeroRows() {
        when(planDetailRepository.findByPlanIdOrderByDayOfWeekAscSeqAsc(7L))
                .thenReturn(List.of(detail(101L, 7))); // 只有周日有安排
        when(planAdherenceRepository.findByUserIdAndPlanIdAndDate(eq(1L), eq(7L), any()))
                .thenReturn(Optional.empty());
        when(planAdherenceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // 以周一为"今天"，往前 7 天正好覆盖上一个周日
        service.ensureMissedDays(1L, date.plusDays(1));

        ArgumentCaptor<PlanAdherence> captor = ArgumentCaptor.forClass(PlanAdherence.class);
        verify(planAdherenceRepository, org.mockito.Mockito.atLeast(1)).save(captor.capture());
        assertThat(captor.getAllValues()).allMatch(a -> a.getPlannedItems() == 1 && a.getDoneItems() == 0);
        assertThat(captor.getAllValues())
                .allMatch(a -> a.getDate().getDayOfWeek().getValue() == 7);
    }

    // ---------- helpers ----------

    private DailyRecord daily(double food, double burn) {
        return DailyRecord.builder().id(1L).userId(1L).date(date)
                .foodIntake(food).exerciseBurn(burn).targetCalories(1800.0).weight(65.0).build();
    }

    private PlanDetail detail(Long id, int dow) {
        return PlanDetail.builder().id(id).planId(7L).dayOfWeek(dow)
                .exerciseId(1L).exerciseName("动作" + id).muscleGroup("胸")
                .sets(3).reps("10").restSec(60).rpe(7).seq(1).estCalories(50.0).build();
    }

    private WorkoutRecord done(Long detailId, int minutes, double calories, int rpe, int pain) {
        return WorkoutRecord.builder().userId(1L).date(date).planDetailId(detailId)
                .status("DONE").duration(minutes).caloriesBurned(calories).rpe(rpe).painFlag(pain).build();
    }

    private WorkoutRecord skipped(Long detailId) {
        return WorkoutRecord.builder().userId(1L).date(date).planDetailId(detailId)
                .status("SKIPPED").duration(0).caloriesBurned(0.0).painFlag(0).build();
    }
}