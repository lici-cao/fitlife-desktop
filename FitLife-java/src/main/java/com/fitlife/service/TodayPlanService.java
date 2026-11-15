package com.fitlife.service;

import com.fitlife.common.BusinessException;
import com.fitlife.dto.TodayPlanVO;
import com.fitlife.entity.Exercise;
import com.fitlife.entity.Plan;
import com.fitlife.entity.PlanDetail;
import com.fitlife.entity.UserProfile;
import com.fitlife.entity.WorkoutRecord;
import com.fitlife.repository.ExerciseRepository;
import com.fitlife.repository.UserProfileRepository;
import com.fitlife.repository.WorkoutRecordRepository;
import com.fitlife.util.BodyMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 今日训练卡（P4 图 4 ①）。
 *
 * <p>把"今天该练什么"从计划明细 + 档案 + 打卡状态一次性组装好，前端只渲染：
 * 每个动作带目标心率区间（Karvonen）、禁忌命中提示（分级）、是否已打卡。
 * 之前的实现里这些信息散落在 PlanVO 与前端计算中，且没有心率口径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TodayPlanService {

    private static final String[] DAY_LABELS = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private final PlanService planService;
    private final ExerciseRepository exerciseRepository;
    private final UserProfileRepository userProfileRepository;
    private final WorkoutRecordRepository workoutRecordRepository;
    private final DailySummaryService dailySummaryService;

    /**
     * 今日训练卡。
     *
     * @param dateStr 可选，缺省今天；支持补看往期某一天
     */
    public TodayPlanVO today(Long userId, String dateStr) {
        LocalDate date = parseDate(dateStr);
        int dow = date.getDayOfWeek().getValue();

        Optional<Plan> planOpt = dailySummaryService.resolvePlanFor(userId, date);
        if (planOpt.isEmpty()) {
            throw new BusinessException(400, "当前没有可执行的训练计划，请先生成计划");
        }
        Plan plan = planOpt.get();
        UserProfile profile = userProfileRepository.findByUserId(userId).orElse(null);

        Integer age = profile != null && profile.getAge() != null ? profile.getAge() : 30;
        Integer hrRest = profile != null ? profile.getHrRest() : null;
        double weightKg = profile != null && profile.getWeight() != null && profile.getWeight() > 0
                ? profile.getWeight() : 65.0;
        Set<String> injuries = PlanSupport.parseCsv(profile == null ? null : profile.getInjuries(), null);

        int hrLow = BodyMetrics.targetBpm(age, hrRest, 0.50);
        int hrHigh = BodyMetrics.targetBpm(age, hrRest, 0.70);

        List<PlanDetail> details = dailySummaryService.detailsById(plan.getId()).values().stream()
                .filter(d -> Objects.equals(d.getDayOfWeek(), dow))
                .sorted(Comparator.comparing(d -> d.getSeq() == null ? 0 : d.getSeq()))
                .toList();

        Map<Long, List<WorkoutRecord>> byDetail = new HashMap<>();
        for (WorkoutRecord w : workoutRecordRepository.findByUserIdAndDate(userId, date)) {
            if (w.getPlanDetailId() != null) {
                byDetail.computeIfAbsent(w.getPlanDetailId(), k -> new ArrayList<>()).add(w);
            }
        }

        int doneItems = 0;
        int minutes = 0;
        double calories = 0.0;
        List<TodayPlanVO.TodayItem> items = new ArrayList<>();

        for (PlanDetail d : details) {
            Exercise ex = d.getExerciseId() == null
                    ? null : exerciseRepository.findById(d.getExerciseId()).orElse(null);
            List<WorkoutRecord> recs = byDetail.getOrDefault(d.getId(), List.of());

            boolean done = recs.stream().anyMatch(w ->
                    CheckInService.STATUS_DONE.equalsIgnoreCase(w.getStatus())
                            || CheckInService.STATUS_PARTIAL.equalsIgnoreCase(w.getStatus()));
            String status = recs.isEmpty() ? "PENDING" : recs.get(recs.size() - 1).getStatus();
            int actualMinutes = recs.stream().mapToInt(w -> w.getDuration() == null ? 0 : w.getDuration()).sum();
            double actualCalories = recs.stream()
                    .mapToDouble(w -> w.getCaloriesBurned() == null ? 0.0 : w.getCaloriesBurned()).sum();
            Integer actualRpe = recs.isEmpty() ? null : recs.get(recs.size() - 1).getRpe();
            if (done) {
                doneItems++;
            }
            minutes += actualMinutes;
            calories += actualCalories;

            TodayPlanVO.TodayItem item = new TodayPlanVO.TodayItem();
            item.setPlanDetailId(d.getId());
            item.setExerciseId(d.getExerciseId());
            item.setExerciseName(d.getExerciseName());
            item.setMuscleGroup(d.getMuscleGroup());
            item.setCategory(ex == null ? null : ex.getCategory());
            item.setSets(d.getSets());
            item.setReps(d.getReps());
            item.setRestSec(d.getRestSec());
            item.setRpe(d.getRpe());
            item.setSeq(d.getSeq());
            item.setEstCalories(d.getEstCalories());
            item.setDescription(ex == null ? null : ex.getDescription());
            item.setContraindication(ex == null ? null : ex.getContraindication());
            item.setDone(done);
            item.setStatus(status);
            item.setActualMinutes(actualMinutes);
            item.setActualCalories(round1(actualCalories));
            item.setActualRpe(actualRpe);

            // 禁忌命中：ERROR 级（该动作与伤病直接冲突）
            if (ex != null && !injuries.isEmpty() && PlanSupport.hitContraindication(ex, injuries)) {
                item.setWarningLevel("ERROR");
                item.setWarningText("该动作与你的伤病史（" + String.join("、", injuries) + "）冲突，建议跳过或换成同肌群替代动作");
            } else if (d.getRpe() != null && d.getRpe() >= 9) {
                item.setWarningLevel("WARN");
                item.setWarningText("强度较高（RPE " + d.getRpe() + "），注意组间休息与动作质量");
            }
            items.add(item);
        }

        boolean restDay = items.isEmpty();
        int planned = items.size();
        int adherence = planned > 0 ? (int) Math.round(doneItems * 100.0 / planned) : 0;

        TodayPlanVO vo = new TodayPlanVO();
        vo.setPlanId(plan.getId());
        vo.setVersion(plan.getVersion() == null ? 1 : plan.getVersion());
        vo.setPlanTitle(plan.getTitle());
        vo.setPlanDay(dow);
        vo.setDayLabel(dayLabel(dow));
        vo.setFocus(restDay ? "休息日" : firstNonBlank(items.get(0).getMuscleGroup(), "综合"));
        vo.setRestDay(restDay);
        vo.setPlannedItems(planned);
        vo.setDoneItems(doneItems);
        vo.setAdherenceRate(adherence);
        vo.setMinutes(minutes);
        vo.setCalories(round1(calories));
        vo.setHrLow(hrLow);
        vo.setHrHigh(hrHigh);
        vo.setHrNote("靶心率 " + hrLow + "–" + hrHigh + " bpm（Karvonen，50%–70% 心率储备；年龄 " + age
                + "，静息 " + BodyMetrics.hrRestOrDefault(hrRest) + "）");
        vo.setItems(items);
        return vo;
    }

    private static String dayLabel(int dow) {
        return dow >= 1 && dow <= 7 ? DAY_LABELS[dow] : "";
    }

    private static String firstNonBlank(String a, String b) {
        return a == null || a.isBlank() ? b : a;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (RuntimeException e) {
            throw new BusinessException(400, "日期格式应为 yyyy-MM-dd");
        }
    }
}