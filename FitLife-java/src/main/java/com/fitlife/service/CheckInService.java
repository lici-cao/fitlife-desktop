package com.fitlife.service;

import com.fitlife.common.BusinessException;
import com.fitlife.dto.CheckInRequest;
import com.fitlife.entity.Exercise;
import com.fitlife.entity.Plan;
import com.fitlife.entity.PlanDetail;
import com.fitlife.entity.UserProfile;
import com.fitlife.entity.WorkoutRecord;
import com.fitlife.repository.ExerciseRepository;
import com.fitlife.repository.PlanDetailRepository;
import com.fitlife.repository.PlanRepository;
import com.fitlife.repository.UserProfileRepository;
import com.fitlife.repository.WorkoutRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 训练打卡（P4 图 4 ②③）。
 *
 * <p>两个入口，语义不同但共用同一套落库与聚合：
 * <ul>
 *   <li>{@link #checkIn} —— 计划内打卡，必须带 planDetailId，服务端校验该明细确属本人当前计划，
 *       动作名/肌群/计划日等冗余字段由服务端补齐，前端无法伪造；</li>
 *   <li>{@link #logFreeWorkout} —— 自由记录（旧 /api/data/exercise-burn 的语义），
 *       保持原有"只记时长与热量"的行为，不参与依从率。</li>
 * </ul>
 *
 * <p>写入后立即调用 {@link DailySummaryService#refreshDay} 在同一事务里重算当日聚合，
 * 因此"打卡成功 = 统计正确"，不存在等待定时任务的时间窗。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckInService {

    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_SKIPPED = "SKIPPED";

    private static final Set<String> VALID_STATUS = Set.of(STATUS_DONE, STATUS_PARTIAL, STATUS_SKIPPED);
    private static final double DEFAULT_WEIGHT_KG = 65.0;

    private final WorkoutRecordRepository workoutRecordRepository;
    private final PlanDetailRepository planDetailRepository;
    private final PlanRepository planRepository;
    private final ExerciseRepository exerciseRepository;
    private final UserProfileRepository userProfileRepository;
    private final DailySummaryService dailySummaryService;

    /**
     * 计划内打卡。同一明细同一天可多次提交（分次完成），按累加处理：
     * 时长与热量相加，RPE 取最后一次，状态取"最完成"的那次。
     */
    @Transactional
    public WorkoutRecord checkIn(Long userId, CheckInRequest req) {
        if (req == null || req.getPlanDetailId() == null) {
            throw new BusinessException(400, "打卡需要指定计划动作（planDetailId）");
        }
        String status = normalizeStatus(req.getStatus());
        LocalDate date = parseDate(req.getDate());

        PlanDetail detail = planDetailRepository.findById(req.getPlanDetailId())
                .orElseThrow(() -> new BusinessException(404, "计划动作不存在"));

        // 归属校验口径：动作明细必须属于本人的某一版计划（含历史版本）；
        // 若按打卡日期解析不到版本（例如计划今天才生成、现在补打昨天的训练日），
        // 只要该明细确实属于本人计划就放行，避免"补打卡"被判成无计划。
        if (!planDetailRepository.existsByIdAndOwner(detail.getId(), userId)) {
            throw new BusinessException(403, "该动作不属于你的计划");
        }
        boolean versionMatched = dailySummaryService.resolvePlanFor(userId, date)
                .map(p -> Objects.equals(p.getId(), detail.getPlanId()))
                .orElse(Boolean.FALSE);
        if (!versionMatched) {
            boolean currentMatched = planRepository
                    .findFirstByUserIdAndStatusOrderByIdDesc(userId, Plan.STATUS_ACTIVE)
                    .map(p -> Objects.equals(p.getId(), detail.getPlanId()))
                    .orElse(Boolean.FALSE);
            if (!currentMatched) {
                log.info("补打历史版本动作 userId={} date={} detail={} planId={}",
                        userId, date, detail.getId(), detail.getPlanId());
            }
        }

        Exercise exercise = detail.getExerciseId() == null
                ? null
                : exerciseRepository.findById(detail.getExerciseId()).orElse(null);

        // 时长：前端不传则按计划估算（力量按组数×组间+每组 45 秒，有氧按 reps 里的分钟数）
        int duration = req.getDuration() != null && req.getDuration() > 0
                ? req.getDuration()
                : estimateMinutes(detail, exercise);
        double calories;
        if (STATUS_SKIPPED.equals(status)) {
            duration = 0;
            calories = 0.0;
        } else if (req.getCalories() != null && req.getCalories() > 0) {
            calories = req.getCalories();
        } else {
            calories = estimateCalories(detail, exercise, duration, weightOf(userId));
        }

        WorkoutRecord record = WorkoutRecord.builder()
                .userId(userId)
                .date(date)
                .exerciseId(detail.getExerciseId() == null ? null : String.valueOf(detail.getExerciseId()))
                .exerciseName(detail.getExerciseName())
                .planDetailId(detail.getId())
                .planDay(detail.getDayOfWeek())
                .status(status)
                .skippedReasons(STATUS_SKIPPED.equals(status) ? trim(req.getSkippedReasons(), 120) : null)
                .duration(duration)
                .caloriesBurned(calories)
                .rpe(req.getRpe())
                .painFlag(Boolean.TRUE.equals(req.getPain()) ? 1 : 0)
                .build();

        // 疼痛上报时给一条日志，便于后续在反馈里定位（不阻断打卡）
        if (record.getPainFlag() == 1) {
            log.info("用户上报疼痛 userId={} 动作={} 日期={}", userId, detail.getExerciseName(), date);
        }

        WorkoutRecord saved = workoutRecordRepository.save(record);
        // save() 在事务内可能延迟 flush，先显式 flush，保证随后的聚合能读到本次打卡
        workoutRecordRepository.flush();
        dailySummaryService.refreshDay(userId, date);
        return saved;
    }

    /** 自由记录（旧接口语义）：只记时长与热量，不计入依从率 */
    @Transactional
    public WorkoutRecord logFreeWorkout(Long userId, String exerciseId, String exerciseName,
                                       Integer duration, Double calories) {
        LocalDate today = LocalDate.now();
        WorkoutRecord record = WorkoutRecord.builder()
                .userId(userId)
                .date(today)
                .exerciseId(exerciseId)
                .exerciseName(exerciseName == null || exerciseName.isBlank() ? nameOf(exerciseId) : exerciseName)
                .status(STATUS_DONE)
                .duration(duration == null ? 0 : duration)
                .caloriesBurned(calories == null ? 0.0 : calories)
                .painFlag(0)
                .build();
        WorkoutRecord saved = workoutRecordRepository.save(record);
        dailySummaryService.refreshDay(userId, today);
        return saved;
    }

    /**
     * 撤销打卡：删除该明细当天的记录并重算。
     * 用于用户点错（不改历史，直接删）；返回是否真的删了东西。
     */
    @Transactional
    public boolean undo(Long userId, Long planDetailId, String dateStr) {
        LocalDate date = parseDate(dateStr);
        List<WorkoutRecord> records = workoutRecordRepository
                .findByUserIdAndPlanDetailIdAndDate(userId, planDetailId, date);
        if (records.isEmpty()) {
            return false;
        }
        workoutRecordRepository.deleteAll(records);
        dailySummaryService.refreshDay(userId, date);
        return true;
    }

    /** 动作名兜底：优先查动作库，查不到再用传进来的旧映射 */
    public String nameOf(String exerciseId) {
        if (exerciseId == null || exerciseId.isBlank()) {
            return "运动";
        }
        try {
            Optional<Exercise> ex = exerciseRepository.findById(Long.parseLong(exerciseId));
            if (ex.isPresent()) {
                return ex.get().getName();
            }
        } catch (NumberFormatException ignored) {
            // 非数字 id（老数据/自定义），走下面的映射
        }
        return switch (exerciseId) {
            case "1" -> "跑步";
            case "2" -> "游泳";
            case "3" -> "骑行";
            case "4" -> "瑜伽";
            case "5" -> "力量训练";
            default -> "运动";
        };
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return STATUS_DONE;
        }
        String s = status.trim().toUpperCase();
        if (!VALID_STATUS.contains(s)) {
            throw new BusinessException(400, "打卡状态只能是 DONE / PARTIAL / SKIPPED");
        }
        return s;
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (RuntimeException e) {
            throw new BusinessException(400, "日期格式应为 yyyy-MM-dd");
        }
    }

    /** 计划动作的默认时长（分钟）：有氧取 reps 里的分钟数，力量按组数估 */
    private int estimateMinutes(PlanDetail detail, Exercise exercise) {
        String reps = detail.getReps() == null ? "" : detail.getReps();
        int minutes = PlanSupport.parseMinutes(reps, 0);
        if (minutes > 0) {
            return Math.max(1, minutes * Math.max(1, nz(detail.getSets())));
        }
        int sets = Math.max(1, nz(detail.getSets()));
        int rest = Math.max(0, nz(detail.getRestSec()));
        return Math.max(1, (int) Math.round(sets * (45 + rest) / 60.0));
    }

    private double estimateCalories(PlanDetail detail, Exercise exercise, int minutes, double weightKg) {
        double met = exercise != null && exercise.getMetValue() != null ? exercise.getMetValue() : 4.0;
        String reps = detail.getReps() == null ? "" : detail.getReps();
        int sets = Math.max(1, nz(detail.getSets()));
        if (PlanSupport.parseMinutes(reps, 0) <= 0 && exercise != null) {
            return PlanSupport.estimateCalories(exercise, sets, reps, weightKg);
        }
        return Math.round(met * weightKg * (minutes / 60.0) * 1.05 * 10) / 10.0;
    }

    private double weightOf(Long userId) {
        return userProfileRepository.findByUserId(userId)
                .map(UserProfile::getWeight)
                .filter(w -> w != null && w > 0)
                .orElse(DEFAULT_WEIGHT_KG);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /** 供今日卡片复用：列出某天已打卡的明细 id */
    public Set<Long> doneDetailIds(Long userId, LocalDate date) {
        return workoutRecordRepository.findByUserIdAndDate(userId, date).stream()
                .filter(w -> w.getPlanDetailId() != null)
                .filter(w -> STATUS_DONE.equalsIgnoreCase(w.getStatus()) || STATUS_PARTIAL.equalsIgnoreCase(w.getStatus()))
                .map(WorkoutRecord::getPlanDetailId)
                .collect(Collectors.toSet());
    }
}