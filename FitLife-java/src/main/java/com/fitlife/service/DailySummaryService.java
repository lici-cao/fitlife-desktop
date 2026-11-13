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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 执行闭环的汇总引擎（对应图 4 第 ③ 环「幂等写入与聚合」）。
 * <p>任何一次打卡/饮食/自由运动写入后，都在同一事务内重算该日汇总，保证：
 * <ol>
 *   <li>plan_adherence 按 (user_id, plan_id, date) 幂等 upsert，重复提交不会重复计数；</li>
 *   <li>daily_records 的汇总列（净热量 / 训练分钟 / 应做 / 完成 / RPE / 疼痛）与明细始终一致；</li>
 *   <li>Dashboard 只读这些汇总列，统计口径唯一，不再依赖前端本地计算。</li>
 * </ol>
 *
 * <p>为什么不放前端算：依从率需要「当天计划条目」这个分母，而计划会随版本变化，
 * 只有服务端按版本+日期回溯才能得到稳定的历史分母。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailySummaryService {

    private final DailyRecordRepository dailyRecordRepository;
    private final FoodIntakeRepository foodIntakeRepository;
    private final WorkoutRecordRepository workoutRecordRepository;
    private final PlanRepository planRepository;
    private final PlanDetailRepository planDetailRepository;
    private final PlanAdherenceRepository planAdherenceRepository;

    /**
     * 重算某用户某天的全部聚合值（幂等：同一输入重复调用结果一致）。
     *
     * @param userId 用户
     * @param date   业务日期
     * @return 重算后的当日汇总
     */
    @Transactional
    public DailyRecord refreshDay(Long userId, LocalDate date) {
        DailyRecord record = dailyRecordRepository.findByUserIdAndDate(userId, date)
                .orElseGet(() -> dailyRecordRepository.save(DailyRecord.builder()
                        .userId(userId)
                        .date(date)
                        .foodIntake(0.0)
                        .exerciseBurn(0.0)
                        .targetCalories(defaultTarget(userId))
                        .weight(0.0)
                        .build()));

        double intake = foodIntakeRepository.findByUserIdAndDate(userId, date).stream()
                .mapToDouble(f -> f.getCalories() == null ? 0.0 : f.getCalories())
                .sum();
        double burn = workoutRecordRepository.findByUserIdAndDate(userId, date).stream()
                .mapToDouble(w -> w.getCaloriesBurned() == null ? 0.0 : w.getCaloriesBurned())
                .sum();
        int minutes = workoutRecordRepository.findByUserIdAndDate(userId, date).stream()
                .mapToInt(w -> w.getDuration() == null ? 0 : w.getDuration())
                .sum();

        record.setFoodIntake(round1(intake));
        record.setExerciseBurn(round1(burn));
        record.setNetCalories(round1(intake - burn));
        record.setWorkoutMinutes(minutes);

        Optional<Plan> planOpt = resolvePlanFor(userId, date);
        List<WorkoutRecord> dayRecords = workoutRecordRepository.findByUserIdAndDate(userId, date);
        // 补打卡口径：当天按历史版本解析不到计划（例如计划今天才生成、现在补打昨天的训练日），
        // 但当天确实有指向计划明细的打卡记录时，借用当前 ACTIVE 版本算应做数，
        // 否则"练了却算没练"，依从率分母永远为 0。
        if (planOpt.isEmpty() && dayRecords.stream().anyMatch(w -> w.getPlanDetailId() != null)) {
            planOpt = planRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, "ACTIVE");
        }
        Map<Long, PlanDetail> detailMap = planOpt.isPresent()
                ? detailsById(planOpt.get().getId())
                : java.util.Collections.emptyMap();
        int dow = date.getDayOfWeek().getValue();
        int plannedItems = 0;
        if (planOpt.isPresent()) {
            final int dowForFilter = dow;
            plannedItems = (int) detailMap.values().stream()
                    .filter(d -> d.getDayOfWeek() != null && d.getDayOfWeek() == dowForFilter)
                    .count();
            for (WorkoutRecord w : dayRecords) {
                if (w.getPlanDetailId() == null) {
                    continue;
                }
                PlanDetail pd = detailMap.get(w.getPlanDetailId());
                if (pd != null && pd.getDayOfWeek() != null && pd.getDayOfWeek() != dowForFilter) {
                    final int punchedDow = pd.getDayOfWeek();
                    plannedItems += (int) detailMap.values().stream()
                            .filter(d -> d.getDayOfWeek() != null && d.getDayOfWeek() == punchedDow)
                            .count();
                    break;
                }
            }
        }
        int doneItems = (int) dayRecords.stream()
                .filter(w -> w.getPlanDetailId() != null)
                .filter(w -> "DONE".equalsIgnoreCase(w.getStatus()) || "PARTIAL".equalsIgnoreCase(w.getStatus()))
                .count();
        int skippedItems = (int) dayRecords.stream()
                .filter(w -> w.getPlanDetailId() != null)
                .filter(w -> "SKIPPED".equalsIgnoreCase(w.getStatus()))
                .count();
        boolean pain = dayRecords.stream().anyMatch(w -> Objects.equals(w.getPainFlag(), 1));

        List<Integer> rpes = dayRecords.stream()
                .map(WorkoutRecord::getRpe)
                .filter(Objects::nonNull)
                .toList();
        Double avgRpe = rpes.isEmpty()
                ? null
                : round1(rpes.stream().mapToInt(Integer::intValue).average().orElse(0));

        // 完成数不能超过应做数（自由记录不计入）
        record.setPlannedItems(plannedItems);
        record.setCompletedItems(Math.min(doneItems, plannedItems));
        record.setAvgRpe(avgRpe);
        record.setPainFlag(pain ? 1 : 0);

        // 显式保存：休息日/当日无安排时 plannedItems 会被清回 0，避免一级缓存脏读
        dailyRecordRepository.save(record);

        if (planOpt.isPresent() && plannedItems > 0) {
            Long planId = planOpt.get().getId();
            PlanAdherence adherence = planAdherenceRepository
                    .findByUserIdAndPlanIdAndDate(userId, planId, date)
                    .orElseGet(() -> PlanAdherence.builder()
                            .userId(userId)
                            .planId(planId)
                            .date(date)
                            .build());
            adherence.setPlannedItems(plannedItems);
            adherence.setDoneItems(Math.min(doneItems, plannedItems));
            adherence.setSkippedItems(Math.min(skippedItems, plannedItems));
            adherence.setMinutes(minutes);
            adherence.setCalories(round1(burn));
            adherence.setAvgRpe(avgRpe);
            adherence.setPainFlag(pain ? 1 : 0);
            try {
                planAdherenceRepository.save(adherence);
            } catch (RuntimeException e) {
                log.warn("依从率快照写入失败 userId={} date={} : {}", userId, date, e.getMessage());
            }
        }

        return record;
    }

    /**
     * 找出某天应遵从的计划版本，用于算「当天应做几项」这个分母。
     *
     * <p>用显式循环实现，避免流式写法在「早于全部版本」的边界上误命中：
     * <ol>
     *   <li>当天 == 今天：优先 ACTIVE，退化到最近一版（覆盖「今天生成、今天打卡」）；</li>
     *   <li>取创建日期不晚于当天的最近一版（历史日期当时的真实执行口径）；</li>
     *   <li>当天早于全部版本时返回空：不能把未来才有的安排算成历史应做项，
     *       否则依从率会被凭空压低。</li>
     * </ol>
     */
    public Optional<Plan> resolvePlanFor(Long userId, LocalDate date) {
        if (date.equals(LocalDate.now())) {
            Optional<Plan> activeNow = planRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, "ACTIVE");
            if (activeNow.isPresent()) {
                return activeNow;
            }
            Optional<Plan> recentNow = planRepository.findByUserIdOrderByIdDesc(userId).stream().findFirst();
            if (recentNow.isPresent()) {
                return recentNow;
            }
        }
        List<Plan> all = planRepository.findByUserIdOrderByIdAsc(userId);
        Plan best = null;
        boolean anyDated = false;
        for (Plan p : all) {
            if (p.getCreatedAt() == null) {
                continue;
            }
            anyDated = true;
            LocalDate created = p.getCreatedAt().toLocalDate();
            if (created.isAfter(date)) {
                continue;
            }
            if (best == null || p.getId() > best.getId()) {
                best = p;
            }
        }
        if (best != null) {
            return Optional.of(best);
        }
        if (anyDated) {
            return Optional.empty();
        }
        Optional<Plan> active = planRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, "ACTIVE");
        if (active.isPresent()) {
            return active;
        }
        Plan last = null;
        for (Plan p : all) {
            if (last == null || p.getId() > last.getId()) {
                last = p;
            }
        }
        return Optional.ofNullable(last);
    }

    /**
     * 统计口径兜底：某天的 [应做项数, 已完成项数]。
     * 依从率快照缺失（历史日从未被打开、或补录失败）时用它现算，保证分母不为 0 也不虚高。
     */
    public int[] plannedAndDone(Long userId, LocalDate date) {
        Optional<Plan> planOpt = resolvePlanFor(userId, date);
        List<WorkoutRecord> dayRecords = workoutRecordRepository.findByUserIdAndDate(userId, date);
        if (planOpt.isEmpty() && dayRecords.stream().anyMatch(w -> w.getPlanDetailId() != null)) {
            // 与 refreshDay 同一口径：补打卡借当前 ACTIVE 版本算分母
            planOpt = planRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, "ACTIVE");
        }
        int dow = date.getDayOfWeek().getValue();
        int planned = 0;
        if (planOpt.isPresent()) {
            planned = (int) detailsById(planOpt.get().getId()).values().stream()
                    .filter(d -> d.getDayOfWeek() != null && d.getDayOfWeek() == dow)
                    .count();
        }
        int done = (int) dayRecords.stream()
                .filter(w -> w.getPlanDetailId() != null)
                .filter(w -> "DONE".equalsIgnoreCase(w.getStatus()) || "PARTIAL".equalsIgnoreCase(w.getStatus()))
                .count();
        return new int[]{planned, Math.min(done, planned)};
    }

    /**
     * 批量重算一个日期区间的汇总（报表页回填历史用）。
     */
    @Transactional
    public int refreshRange(Long userId, LocalDate start, LocalDate end) {
        int n = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            refreshDay(userId, d);
            n++;
        }
        return n;
    }

    /**
     * 补齐最近 7 天里没有打卡记录的「零完成」快照，
     * 否则用户不打开 App 的那几天会被误认为「没有应做项」而抬高依从率。
     */
    @Transactional
    public void ensureMissedDays(Long userId, LocalDate today) {
        int dow = today.getDayOfWeek().getValue();
        for (int i = 1; i <= 7; i++) {
            LocalDate d = today.minusDays(i);
            Optional<Plan> planOpt = resolvePlanFor(userId, d);
            if (planOpt.isEmpty()) {
                continue;
            }
            Long planId = planOpt.get().getId();
            int dayOfWeek = d.getDayOfWeek().getValue();
            long planned = planDetailRepository.findByPlanIdOrderByDayOfWeekAscSeqAsc(planId).stream()
                    .filter(pd -> pd.getDayOfWeek() != null && pd.getDayOfWeek() == dayOfWeek)
                    .count();
            if (planned == 0) {
                continue;
            }
            if (planAdherenceRepository.findByUserIdAndPlanIdAndDate(userId, planId, d).isPresent()) {
                continue;
            }
            planAdherenceRepository.save(PlanAdherence.builder()
                    .userId(userId)
                    .planId(planId)
                    .date(d)
                    .plannedItems((int) planned)
                    .doneItems(0)
                    .skippedItems(0)
                    .minutes(0)
                    .calories(0.0)
                    .painFlag(0)
                    .build());
        }
        log.debug("补录零完成快照：userId={}", userId);
    }

    /**
     * 取某个计划的全部明细，按明细 id 建索引（同日聚合与打卡校验共用）。
     */
    public Map<Long, PlanDetail> detailsById(Long planId) {
        List<PlanDetail> list = planDetailRepository.findByPlanIdOrderByDayOfWeekAscSeqAsc(planId);
        return list.stream()
                .collect(Collectors.toMap(PlanDetail::getId, Function.identity(), (a, b) -> a));
    }

    private double defaultTarget(Long userId) {
        return 1800.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * 按日期汇总饮食热量（统计接口复用，避免重复查询）。
     */
    public Map<LocalDate, Double> intakeByDate(Long userId, LocalDate start, LocalDate end) {
        List<FoodIntake> list = foodIntakeRepository.findByUserIdAndDateBetween(userId, start, end);
        return list.stream().collect(Collectors.groupingBy(
                FoodIntake::getDate,
                Collectors.summingDouble(f -> f.getCalories() == null ? 0.0 : f.getCalories())));
    }

    /**
     * 按日期归组训练记录（统计接口复用）。
     */
    public Map<LocalDate, List<WorkoutRecord>> workoutsByDate(Long userId, LocalDate start, LocalDate end) {
        Map<LocalDate, List<WorkoutRecord>> map = new java.util.LinkedHashMap<>();
        for (WorkoutRecord w : workoutRecordRepository.findByUserIdAndDateBetween(userId, start, end)) {
            map.computeIfAbsent(w.getDate(), k -> new ArrayList<>()).add(w);
        }
        return map;
    }
}