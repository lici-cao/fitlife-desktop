package com.fitlife.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.dto.StatsSummaryVO;
import com.fitlife.entity.PlanAdherence;
import com.fitlife.entity.UserProfile;
import com.fitlife.entity.WorkoutRecord;
import com.fitlife.repository.KnowledgeRuleRepository;
import com.fitlife.repository.PlanAdherenceRepository;
import com.fitlife.repository.UserProfileRepository;
import com.fitlife.repository.WorkoutRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 统计与依从性（P4 图 4 ④）—— 服务端唯一口径。
 *
 * <p>为什么要有这个类：P1~P3 阶段 Dashboard 的所有数字都由前端用 localStorage 自算，
 * 图表与数据库可能长期不一致（G6）。这里把"完成率 / 训练量 / 热量收支 / RPE 偏差"
 * 统一成一次聚合查询的结果，前端只负责画图。
 *
 * <p>风险分级（图 4 ⑤）：输出 riskNotes 时区分 INFO / WARN / DANGER 三档，
 * 阈值优先读 knowledge_rule 的 ADVICE_LOAD，读不到用内置默认，保证离线可用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    /** 内置默认阈值（与 V4 迁移写入的 ADVICE_LOAD 保持一致） */
    private static final double DEFAULT_ADHERENCE_LOW = 70.0;
    private static final double DEFAULT_ADHERENCE_HIGH = 90.0;
    private static final double DEFAULT_RPE_OVER = 1.0;
    private static final double DEFAULT_RPE_UNDER = -1.5;
    private static final int DEFAULT_MIN_SESSIONS = 2;

    private final PlanAdherenceRepository planAdherenceRepository;
    private final WorkoutRecordRepository workoutRecordRepository;
    private final UserProfileRepository userProfileRepository;
    private final KnowledgeRuleRepository knowledgeRuleRepository;
    private final DailySummaryService dailySummaryService;
    private final ObjectMapper objectMapper;

    /**
     * 区间统计。
     *
     * @param days 回溯天数（1~180），endDate 为空时以今天为结束日
     */
    @Transactional
    public StatsSummaryVO summary(Long userId, String endDateStr, Integer days) {
        int window = days == null ? 14 : Math.max(1, Math.min(180, days));
        LocalDate end = parseDate(endDateStr, LocalDate.now());
        LocalDate start = end.minusDays(window - 1L);

        // 历史日期的"应做数"要靠明细推出来：先补未执行日的快照，否则分母漏算
        dailySummaryService.ensureMissedDays(userId, end);

        List<PlanAdherence> adherences = planAdherenceRepository
                .findByUserIdAndDateBetweenOrderByDateAsc(userId, start, end);
        Map<LocalDate, PlanAdherence> adhByDate = new HashMap<>();
        for (PlanAdherence a : adherences) {
            adhByDate.put(a.getDate(), a);
        }

        Map<LocalDate, Double> intakeByDate = dailySummaryService.intakeByDate(userId, start, end);
        Map<LocalDate, List<WorkoutRecord>> workoutsByDate = dailySummaryService.workoutsByDate(userId, start, end);

        long planned = 0;
        long done = 0;
        long skipped = 0;
        long minutes = 0;
        long sessions = 0;
        double burn = 0.0;
        double intake = 0.0;
        int activeDays = 0;
        int painDays = 0;
        List<Integer> actualRpes = new ArrayList<>();
        List<Double> plannedRpes = new ArrayList<>();
        List<StatsSummaryVO.DailyPoint> series = new ArrayList<>();

        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            PlanAdherence a = adhByDate.get(d);
            List<WorkoutRecord> dayWorkouts = workoutsByDate.getOrDefault(d, List.of());

            // 快照缺失时（当天刚生成计划、或该日尚未触发 refreshDay）直接按计划明细重算，
            // 保证分母口径与 refreshDay 保持一致，而不是简单记为 0。
            int[] live = a == null ? dailySummaryService.plannedAndDone(userId, d) : null;
            int p = a == null ? (live == null ? 0 : live[0]) : nz(a.getPlannedItems());
            int dn = a == null
                    ? (live == null ? 0 : live[1])
                    : nz(a.getDoneItems());
            int sk = a == null ? 0 : nz(a.getSkippedItems());
            // 历史上从未写入过快照（例如刚接入 P4 的用户）时，序列里也要体现应做数，
// 否则用户会看到「按天全是 0、汇总却有分母」的不一致
            if (a == null && p == 0) {
                int[] fallback = dailySummaryService.plannedAndDone(userId, d);
                p = fallback[0];
                dn = fallback[1];
            }
            int mins = dayWorkouts.stream().mapToInt(w -> nz(w.getDuration())).sum();
            double dayBurn = dayWorkouts.stream()
                    .mapToDouble(w -> w.getCaloriesBurned() == null ? 0.0 : w.getCaloriesBurned()).sum();
            double dayIntake = intakeByDate.getOrDefault(d, 0.0);

            planned += p;
            done += dn;
            skipped += sk;
            minutes += mins;
            sessions += dayWorkouts.stream().filter(w -> w.getPlanDetailId() != null).count();
            burn += dayBurn;
            intake += dayIntake;
            if (!dayWorkouts.isEmpty()) {
                activeDays++;
            }
            if (a != null && Objects.equals(a.getPainFlag(), 1)) {
                painDays++;
            }

            for (WorkoutRecord w : dayWorkouts) {
                if (w.getRpe() != null) {
                    actualRpes.add(w.getRpe());
                }
            }

            StatsSummaryVO.DailyPoint point = new StatsSummaryVO.DailyPoint();
            point.setDate(d.toString());
            point.setPlannedItems(p);
            point.setDoneItems(dn);
            point.setAdherenceRate(p > 0 ? rateInt(dn, p) : null);
            point.setMinutes(mins);
            point.setBurn(round1(dayBurn));
            point.setIntake(round1(dayIntake));
            point.setNet(round1(dayIntake - dayBurn));
            point.setAvgRpe(a == null ? null : a.getAvgRpe());
            series.add(point);
        }

        int adherenceRate = planned > 0 ? rateInt(done, planned) : 0;

        // RPE 偏差：实际均值 − 计划均值（正数=练得比计划狠，负数=练不动）
        double plannedRpeAvg = planRpeAverage(userId, end);
        double actualRpeAvg = actualRpes.isEmpty()
                ? 0.0
                : actualRpes.stream().mapToInt(Integer::intValue).average().orElse(0);
        Double rpeDeviation = (actualRpes.isEmpty() || plannedRpeAvg <= 0)
                ? null
                : round1(actualRpeAvg - plannedRpeAvg);

        StatsSummaryVO vo = new StatsSummaryVO();
        vo.setStartDate(start.toString());
        vo.setEndDate(end.toString());
        vo.setDays(window);
        vo.setActiveDays(activeDays);
        vo.setPlannedItems((int) planned);
        vo.setDoneItems((int) done);
        vo.setSkippedItems((int) skipped);
        vo.setAdherenceRate(adherenceRate);
        vo.setStreakDays(streakDays(userId, end));
        vo.setTotalMinutes((int) minutes);
        vo.setTotalSessions((int) sessions);
        vo.setTotalBurnCalories(round1(burn));
        vo.setTotalIntakeCalories(round1(intake));
        vo.setNetCalories(round1(intake - burn));
        vo.setAvgNetCalories(window > 0 ? round1((intake - burn) / window) : 0.0);
        vo.setAvgWeeklyMinutes((int) Math.round(minutes * 7.0 / window));
        vo.setAvgActualRpe(actualRpes.isEmpty() ? null : round1(actualRpeAvg));
        vo.setAvgPlannedRpe(plannedRpeAvg <= 0 ? null : round1(plannedRpeAvg));
        vo.setRpeDeviation(rpeDeviation);
        vo.setPainDays(painDays);
        vo.setSeries(series);
        vo.setRisks(buildRisks(adherenceRate, rpeDeviation, painDays, (int) planned, (int) done, window, end));
        return vo;
    }

    /**
     * 风险提示分级（图 4 ⑤）。三档：
     * <ul>
     *   <li>DANGER：有疼痛上报，或依从率跌破下限 —— 必须先处理，不建议加量；</li>
     *   <li>WARN：RPE 明显高于计划（过度训练倾向）或计划明显放水（训练不足）；</li>
     *   <li>INFO：依从率中等、数据不足等提示性信息。</li>
     * </ul>
     */
    public List<StatsSummaryVO.RiskNote> buildRisks(int adherenceRate, Double rpeDeviation,
                                                    int painDays, int planned, int done, int window, LocalDate end) {
        Map<String, Object> cfg = adviceConfig();
        double low = num(cfg, "adherenceLow", DEFAULT_ADHERENCE_LOW);
        double high = num(cfg, "adherenceHigh", DEFAULT_ADHERENCE_HIGH);
        double rpeOver = num(cfg, "rpeOver", DEFAULT_RPE_OVER);
        double rpeUnder = num(cfg, "rpeUnder", DEFAULT_RPE_UNDER);
        int minSessions = (int) num(cfg, "minSessionsForAdvice", DEFAULT_MIN_SESSIONS);

        List<StatsSummaryVO.RiskNote> notes = new ArrayList<>();

        if (painDays > 0) {
            notes.add(risk("DANGER", "PAIN_REPORTED",
                    "近 " + window + " 天有 " + painDays + " 天上报了疼痛或不适",
                    "暂停相关部位的负重动作，先做低强度活动并考虑就医评估"));
        }

        if (planned >= minSessions && adherenceRate < low) {
            notes.add(risk("DANGER", "ADHERENCE_LOW",
                    "依从率 " + fmt(adherenceRate) + "%，低于 " + fmt(low) + "% 的执行下限",
                    "先把频次降到能完成的程度（如每周减一天），或把单次时长缩短到 " + Math.max(20, window) + " 分钟以内"));
        } else if (planned >= minSessions && adherenceRate >= low && adherenceRate < high) {
            notes.add(risk("INFO", "ADHERENCE_MEDIUM",
                    "依从率 " + fmt(adherenceRate) + "%，处于可推进区间",
                    "保持当前安排，先连续两周达标再考虑加量"));
        }

        if (rpeDeviation != null && rpeDeviation > rpeOver) {
            notes.add(risk("WARN", "RPE_HIGH",
                    "实际强度比计划高 " + fmt(rpeDeviation) + " 个 RPE 等级",
                    "降低组数或重量，把主观强度压回计划区间，避免恢复不足"));
        } else if (rpeDeviation != null && rpeDeviation < rpeUnder) {
            notes.add(risk("WARN", "RPE_LOW",
                    "实际强度比计划低 " + fmt(Math.abs(rpeDeviation)) + " 个 RPE 等级",
                    "可能是重量偏轻或跳过了主项，下一轮可适度加量"));
        }

        if (planned == 0) {
            notes.add(risk("INFO", "NO_PLAN_DATA",
                    "所选区间内没有可统计的计划安排",
                    "先生成一份训练计划，打卡后这里会有完成率与训练量"));
        } else if (done == 0 && !end.isAfter(LocalDate.now())) {
            notes.add(risk("INFO", "NO_CHECK_IN",
                    "计划已下发但还没有打卡记录",
                    "在今日训练卡上完成或跳过动作，统计才会生效"));
        }

        notes.sort(Comparator.comparingInt(n -> levelOrder(n.getLevel())));
        return notes;
    }

    /** 连续达标天数：从 end 往前，逐日看是否"有安排且全部完成"，遇到休息日跳过不算断 */
    public int streakDays(Long userId, LocalDate end) {
        List<PlanAdherence> list = planAdherenceRepository
                .findByUserIdAndDateBetweenOrderByDateAsc(userId, end.minusDays(60), end);
        Map<LocalDate, PlanAdherence> byDate = new LinkedHashMap<>();
        for (PlanAdherence a : list) {
            byDate.put(a.getDate(), a);
        }
        int streak = 0;
        for (int i = 0; i < 60; i++) {
            LocalDate d = end.minusDays(i);
            PlanAdherence a = byDate.get(d);
            // 无快照或当天无安排 = 休息日：既不打断连续，也不计入天数
            if (a == null || nz(a.getPlannedItems()) == 0) {
                continue;
            }
            if (nz(a.getDoneItems()) >= nz(a.getPlannedItems())) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    /** 当前生效计划的计划 RPE 均值（用于和实际对比） */
    public double planRpeAverage(Long userId, LocalDate date) {
        return dailySummaryService.resolvePlanFor(userId, date).map(plan -> {
            int dow = date.getDayOfWeek().getValue();
            return dailySummaryService.detailsById(plan.getId()).values().stream()
                    .filter(d -> Objects.equals(d.getDayOfWeek(), dow))
                    .map(d -> d.getRpe())
                    .filter(Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .average()
                    .orElse(0.0);
        }).orElse(0.0);
    }

    /** 读 ADVICE_LOAD 规则的阈值；缺失或损坏时返回空 Map（调用方用内置默认） */
    public Map<String, Object> adviceConfig() {
        try {
            Optional<com.fitlife.entity.KnowledgeRule> rule = knowledgeRuleRepository.findByCode("ADVICE_LOAD");
            if (rule.isEmpty() || rule.get().getExpression() == null) {
                return Map.of();
            }
            JsonNode node = objectMapper.readTree(rule.get().getExpression());
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> {
                if (e.getValue().isNumber()) {
                    map.put(e.getKey(), e.getValue().asDouble());
                } else if (e.getValue().isBoolean()) {
                    map.put(e.getKey(), e.getValue().asBoolean());
                }
            });
            return map;
        } catch (Exception e) {
            log.warn("读取 ADVICE_LOAD 规则失败，使用内置默认阈值：{}", e.getMessage());
            return Map.of();
        }
    }

    /** 供 ProgressService 复用：用户体重（缺失按 65kg） */
    public double weightOf(Long userId) {
        return userProfileRepository.findByUserId(userId)
                .map(UserProfile::getWeight)
                .filter(w -> w != null && w > 0)
                .orElse(65.0);
    }

    private StatsSummaryVO.RiskNote risk(String level, String code, String message, String suggestion) {
        StatsSummaryVO.RiskNote note = new StatsSummaryVO.RiskNote();
        note.setLevel(level);
        note.setCode(code);
        note.setMessage(message);
        note.setSuggestion(suggestion);
        return note;
    }

    private static int levelOrder(String level) {
        return switch (level == null ? "" : level) {
            case "DANGER" -> 0;
            case "WARN" -> 1;
            default -> 2;
        };
    }

    private static double num(Map<String, Object> cfg, String key, double def) {
        Object v = cfg.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    /** 依从率百分比（0-100 整数）；分母为 0 时返回 0 */
    private static int rateInt(long done, long planned) {
        return planned <= 0 ? 0 : (int) Math.round(done * 100.0 / planned);
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(round1(v));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private LocalDate parseDate(String s, LocalDate def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return def;
        }
    }
}