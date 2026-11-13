package com.fitlife.service;

import com.fitlife.common.BusinessException;
import com.fitlife.dto.ExerciseBurnRequest;
import com.fitlife.dto.FoodIntakeRequest;
import com.fitlife.dto.TodayPlanVO;
import com.fitlife.entity.DailyRecord;
import com.fitlife.entity.FoodIntake;
import com.fitlife.entity.UserProfile;
import com.fitlife.entity.WorkoutRecord;
import com.fitlife.repository.CustomFoodRepository;
import com.fitlife.repository.DailyRecordRepository;
import com.fitlife.repository.FoodIntakeRepository;
import com.fitlife.repository.UserProfileRepository;
import com.fitlife.repository.WorkoutRecordRepository;
import com.fitlife.util.BodyMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 体质档案 / 饮食 / 运动 / 自定义食物服务。
 *
 * <p>P4 改造点（其余行为保持不变）：
 * <ol>
 *   <li>{@code getTodayRecord} 不再在 GET 里写库：没有记录时返回一份纯计算出来的
 *       "今日骨架"DTO（含目标热量、净热量、计划应做数），刷新页面不会凭空建行；</li>
 *   <li>{@code addFoodIntake / addExerciseBurn} 统一委托 {@link DailySummaryService#refreshDay}，
 *       聚合口径只有一处实现；运动记录的名称改为查动作库（原先是 switch "1"→跑步 的硬编码）；</li>
 *   <li>{@code getDailyRecords} 输出带服务端聚合字段，Dashboard 不再自算；</li>
 *   <li>{@code syncData} 带今日训练卡摘要，便于客户端一次性初始化。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class DataService {

    private final UserProfileRepository profileRepository;
    private final DailyRecordRepository dailyRecordRepository;
    private final FoodIntakeRepository foodIntakeRepository;
    private final CustomFoodRepository customFoodRepository;
    private final WorkoutRecordRepository workoutRecordRepository;
    private final DailySummaryService dailySummaryService;
    private final CheckInService checkInService;
    private final TodayPlanService todayPlanService;

    public com.fitlife.dto.ApiResponse getProfile(Long userId) {
        UserProfile profile = profileRepository.findByUserId(userId).orElse(null);
        if (profile == null) {
            return com.fitlife.dto.ApiResponse.error("用户档案不存在");
        }
        return com.fitlife.dto.ApiResponse.success(toProfileDTO(profile));
    }

    @Transactional
    public com.fitlife.dto.ApiResponse updateProfile(Long userId, com.fitlife.dto.ProfileDTO dto) {
        UserProfile profile = profileRepository.findByUserId(userId).orElse(new UserProfile());

        profile.setUserId(userId);
        profile.setName(dto.getName());
        profile.setGender(dto.getGender());
        profile.setAge(dto.getAge());
        profile.setHeight(dto.getHeight());
        profile.setWeight(dto.getWeight());
        profile.setBodyFat(dto.getBodyFat());
        profile.setGoal(dto.getGoal());
        profile.setActivityLevel(dto.getActivityLevel());
        profile.setExperience(dto.getExperience());
        profile.setWeeklyDays(dto.getWeeklyDays());
        profile.setSessionMinutes(dto.getSessionMinutes());
        profile.setEquipments(dto.getEquipments());
        profile.setInjuries(dto.getInjuries());
        // P2：静息心率，越界视为未填（生成链路会退回默认 70）
        Integer hrRest = dto.getHrRest();
        profile.setHrRest(hrRest != null && hrRest >= 35 && hrRest <= 110 ? hrRest : null);

        profileRepository.save(profile);
        return com.fitlife.dto.ApiResponse.success("档案更新成功");
    }

    /** 今日记录：只读；没有行时给一份计算出来的骨架，不落库 */
    public com.fitlife.dto.ApiResponse getTodayRecord(Long userId) {
        LocalDate today = LocalDate.now();
        Optional<DailyRecord> record = dailyRecordRepository.findByUserIdAndDate(userId, today);
        if (record.isPresent()) {
            return com.fitlife.dto.ApiResponse.success(toDailyRecordDTO(record.get()));
        }
        return com.fitlife.dto.ApiResponse.success(skeletonToday(userId, today));
    }

    public com.fitlife.dto.ApiResponse getDailyRecords(Long userId, String startDate, String endDate) {
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().minusDays(30);
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

        List<com.fitlife.dto.DailyRecordDTO> dtos = dailyRecordRepository
                .findByUserIdAndDateBetween(userId, start, end).stream()
                .map(this::toDailyRecordDTO)
                .collect(Collectors.toList());

        return com.fitlife.dto.ApiResponse.success(dtos);
    }

    @Transactional
    public com.fitlife.dto.ApiResponse addFoodIntake(Long userId, FoodIntakeRequest request) {
        LocalDate today = LocalDate.now();
        foodIntakeRepository.save(FoodIntake.builder()
                .userId(userId)
                .date(today)
                .calories(request.getCalories())
                .foodId(request.getFoodId())
                .amount(request.getAmount())
                .build());
        dailySummaryService.refreshDay(userId, today);
        return com.fitlife.dto.ApiResponse.success("食物摄入已添加");
    }

    /**
     * 自由运动记录（旧的 /api/data/exercise-burn 语义：只记时长与热量）。
     * 计划内动作请走 /api/data/check-in，才能计入依从率。
     */
    @Transactional
    public com.fitlife.dto.ApiResponse addExerciseBurn(Long userId, ExerciseBurnRequest request) {
        WorkoutRecord saved = checkInService.logFreeWorkout(userId, request.getExerciseId(),
                checkInService.nameOf(request.getExerciseId()), request.getDuration(), request.getCalories());
        return com.fitlife.dto.ApiResponse.success("运动记录已添加",
                Map.of("id", saved.getId(), "caloriesBurned", saved.getCaloriesBurned()));
    }

    public com.fitlife.dto.ApiResponse getWorkoutRecords(Long userId, String startDate, String endDate) {
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().minusDays(30);
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

        List<com.fitlife.dto.WorkoutRecordDTO> dtos = workoutRecordRepository
                .findByUserIdAndDateBetween(userId, start, end).stream()
                .map(this::toWorkoutRecordDTO)
                .collect(Collectors.toList());
        return com.fitlife.dto.ApiResponse.success(dtos);
    }

    public com.fitlife.dto.ApiResponse getCustomFoods(Long userId) {
        List<com.fitlife.dto.CustomFoodDTO> dtos = customFoodRepository.findByUserId(userId).stream()
                .map(this::toCustomFoodDTO)
                .collect(Collectors.toList());
        return com.fitlife.dto.ApiResponse.success(dtos);
    }

    @Transactional
    public com.fitlife.dto.ApiResponse addCustomFood(Long userId, com.fitlife.dto.CustomFoodDTO dto) {
        com.fitlife.entity.CustomFood food = com.fitlife.entity.CustomFood.builder()
                .userId(userId)
                .name(dto.getName())
                .calories(dto.getCalories())
                .protein(dto.getProtein())
                .carbs(dto.getCarbs())
                .fat(dto.getFat())
                .servingSize(dto.getServingSize())
                .unit(dto.getUnit())
                .category(dto.getCategory())
                .build();
        com.fitlife.entity.CustomFood saved = customFoodRepository.save(food);
        dto.setId(saved.getId());
        return com.fitlife.dto.ApiResponse.success("自定义食物已添加", dto);
    }

    @Transactional
    public com.fitlife.dto.ApiResponse deleteCustomFood(Long userId, Long id) {
        com.fitlife.entity.CustomFood food = customFoodRepository.findById(id).orElse(null);
        if (food == null || !food.getUserId().equals(userId)) {
            return com.fitlife.dto.ApiResponse.error("食物不存在或无权限");
        }
        customFoodRepository.delete(food);
        return com.fitlife.dto.ApiResponse.success("自定义食物已删除");
    }

    /**
     * 一次性同步：档案 + 今日记录 + 自定义食物 + 运动记录 + 日记录 + 今日训练卡摘要。
     * 仍保持"全量"语义（客户端初始化用），今日训练卡取不到时不报错，只给 null。
     */
    public com.fitlife.dto.ApiResponse syncData(Long userId) {
        com.fitlife.dto.SyncDataDTO syncData = new com.fitlife.dto.SyncDataDTO();

        profileRepository.findByUserId(userId).ifPresent(p -> syncData.setProfile(toProfileDTO(p)));

        LocalDate today = LocalDate.now();
        DailyRecord todayRecord = dailyRecordRepository.findByUserIdAndDate(userId, today)
                .orElse(null);
        syncData.setTodayRecord(todayRecord == null ? skeletonToday(userId, today) : toDailyRecordDTO(todayRecord));

        syncData.setCustomFoods(customFoodRepository.findByUserId(userId).stream()
                .map(this::toCustomFoodDTO).collect(Collectors.toList()));

        syncData.setWorkoutRecords(workoutRecordRepository.findByUserId(userId).stream()
                .map(this::toWorkoutRecordDTO).collect(Collectors.toList()));

        syncData.setDailyRecords(dailyRecordRepository.findByUserId(userId).stream()
                .map(this::toDailyRecordDTO).collect(Collectors.toList()));

        syncData.setTodayPlan(safeTodayPlan(userId, today));
        return com.fitlife.dto.ApiResponse.success(syncData);
    }

    // ============================================================
    // 内部工具
    // ============================================================

    /** 没有当日记录时的只读骨架：目标热量按档案算，净热量为 0，应做数取今日计划 */
    private com.fitlife.dto.DailyRecordDTO skeletonToday(Long userId, LocalDate date) {
        com.fitlife.dto.DailyRecordDTO dto = new com.fitlife.dto.DailyRecordDTO();
        dto.setDate(date.toString());
        dto.setFoodIntake(0.0);
        dto.setExerciseBurn(0.0);
        dto.setTargetCalories(targetCaloriesOf(userId));
        dto.setWeight(profileRepository.findByUserId(userId)
                .map(UserProfile::getWeight).orElse(null));
        dto.setNetCalories(0.0);
        dto.setWorkoutMinutes(0);
        dto.setCompletedItems(0);
        TodayPlanVO plan = safeTodayPlan(userId, date);
        dto.setPlannedItems(plan == null ? 0 : plan.getPlannedItems());
        dto.setAdherenceRate(0);
        dto.setPain(false);
        return dto;
    }

    private TodayPlanVO safeTodayPlan(Long userId, LocalDate date) {
        try {
            return todayPlanService.today(userId, date.toString());
        } catch (BusinessException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 目标热量口径与生成链路一致（BodyMetrics），档案不全时退回 1800 */
    private double targetCaloriesOf(Long userId) {
        UserProfile p = profileRepository.findByUserId(userId).orElse(null);
        if (p == null) {
            return 1800.0;
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("userProfile", p);
        context.put("goal", p.getGoal());
        BodyMetrics.Metrics m = BodyMetrics.from(context);
        return m != null && m.targetCalories() > 0 ? m.targetCalories() : 1800.0;
    }

    private com.fitlife.dto.DailyRecordDTO toDailyRecordDTO(DailyRecord record) {
        com.fitlife.dto.DailyRecordDTO dto = new com.fitlife.dto.DailyRecordDTO();
        dto.setDate(record.getDate().toString());
        dto.setFoodIntake(record.getFoodIntake());
        dto.setExerciseBurn(record.getExerciseBurn());
        dto.setTargetCalories(record.getTargetCalories());
        dto.setWeight(record.getWeight());
        dto.setNetCalories(record.netOrCompute());
        dto.setWorkoutMinutes(record.getWorkoutMinutes());
        dto.setCompletedItems(record.getCompletedItems());
        dto.setPlannedItems(record.getPlannedItems());
        Integer planned = record.getPlannedItems();
        Integer done = record.getCompletedItems();
        dto.setAdherenceRate(planned == null || planned == 0
                ? 0 : (int) Math.round((done == null ? 0 : done) * 100.0 / planned));
        dto.setAvgRpe(record.getAvgRpe());
        dto.setPain(record.getPainFlag() != null && record.getPainFlag() == 1);
        return dto;
    }

    private com.fitlife.dto.ProfileDTO toProfileDTO(UserProfile profile) {
        com.fitlife.dto.ProfileDTO dto = new com.fitlife.dto.ProfileDTO();
        dto.setName(profile.getName());
        dto.setGender(profile.getGender());
        dto.setAge(profile.getAge());
        dto.setHeight(profile.getHeight());
        dto.setWeight(profile.getWeight());
        dto.setBodyFat(profile.getBodyFat());
        dto.setGoal(profile.getGoal());
        dto.setActivityLevel(profile.getActivityLevel());
        dto.setExperience(profile.getExperience());
        dto.setWeeklyDays(profile.getWeeklyDays());
        dto.setSessionMinutes(profile.getSessionMinutes());
        dto.setEquipments(profile.getEquipments());
        dto.setInjuries(profile.getInjuries());
        dto.setHrRest(profile.getHrRest());
        return dto;
    }

    private com.fitlife.dto.WorkoutRecordDTO toWorkoutRecordDTO(WorkoutRecord record) {
        com.fitlife.dto.WorkoutRecordDTO dto = new com.fitlife.dto.WorkoutRecordDTO();
        dto.setDate(record.getDate().toString());
        dto.setExerciseId(record.getExerciseId());
        dto.setExerciseName(record.getExerciseName());
        dto.setDuration(record.getDuration());
        dto.setCaloriesBurned(record.getCaloriesBurned());
        return dto;
    }

    private com.fitlife.dto.CustomFoodDTO toCustomFoodDTO(com.fitlife.entity.CustomFood food) {
        com.fitlife.dto.CustomFoodDTO dto = new com.fitlife.dto.CustomFoodDTO();
        dto.setId(food.getId());
        dto.setName(food.getName());
        dto.setCalories(food.getCalories());
        dto.setProtein(food.getProtein());
        dto.setCarbs(food.getCarbs());
        dto.setFat(food.getFat());
        dto.setServingSize(food.getServingSize());
        dto.setUnit(food.getUnit());
        dto.setCategory(food.getCategory());
        return dto;
    }

    /** 预留：按函数引用转换的通用工具（当前未使用，保留签名以备扩展） */
    @SuppressWarnings("unused")
    private static <T, R> List<R> mapAll(List<T> src, Function<T, R> fn) {
        return src.stream().map(fn).collect(Collectors.toList());
    }
}