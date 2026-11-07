package com.fitlife.service;

import com.fitlife.dto.*;
import com.fitlife.entity.*;
import com.fitlife.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DataService {

    private final UserProfileRepository profileRepository;
    private final DailyRecordRepository dailyRecordRepository;
    private final FoodIntakeRepository foodIntakeRepository;
    private final CustomFoodRepository customFoodRepository;
    private final WorkoutRecordRepository workoutRecordRepository;

    public ApiResponse getProfile(Long userId) {
        UserProfile profile = profileRepository.findByUserId(userId)
                .orElse(null);
        if (profile == null) {
            return ApiResponse.error("用户档案不存在");
        }

        ProfileDTO dto = new ProfileDTO();
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

        return ApiResponse.success(dto);
    }

    @Transactional
    public ApiResponse updateProfile(Long userId, ProfileDTO dto) {
        UserProfile profile = profileRepository.findByUserId(userId)
                .orElse(new UserProfile());

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

        profileRepository.save(profile);
        return ApiResponse.success("档案更新成功");
    }

    public ApiResponse getTodayRecord(Long userId) {
        LocalDate today = LocalDate.now();
        DailyRecord record = dailyRecordRepository.findByUserIdAndDate(userId, today)
                .orElseGet(() -> createDefaultDailyRecord(userId, today));

        return ApiResponse.success(toDailyRecordDTO(record));
    }

    public ApiResponse getDailyRecords(Long userId, String startDate, String endDate) {
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().minusDays(30);
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

        List<DailyRecord> records = dailyRecordRepository.findByUserIdAndDateBetween(userId, start, end);
        List<DailyRecordDTO> dtos = records.stream()
                .map(this::toDailyRecordDTO)
                .collect(Collectors.toList());

        return ApiResponse.success(dtos);
    }

    @Transactional
    public ApiResponse addFoodIntake(Long userId, FoodIntakeRequest request) {
        LocalDate today = LocalDate.now();

        FoodIntake intake = FoodIntake.builder()
                .userId(userId)
                .date(today)
                .calories(request.getCalories())
                .foodId(request.getFoodId())
                .amount(request.getAmount())
                .build();
        foodIntakeRepository.save(intake);

        // 更新今日记录
        DailyRecord record = dailyRecordRepository.findByUserIdAndDate(userId, today)
                .orElseGet(() -> createDefaultDailyRecord(userId, today));

        List<FoodIntake> todayIntakes = foodIntakeRepository.findByUserIdAndDate(userId, today);
        double totalFoodIntake = todayIntakes.stream()
                .mapToDouble(FoodIntake::getCalories)
                .sum();
        record.setFoodIntake(totalFoodIntake);
        dailyRecordRepository.save(record);

        return ApiResponse.success("食物摄入已添加");
    }

    @Transactional
    public ApiResponse addExerciseBurn(Long userId, ExerciseBurnRequest request) {
        LocalDate today = LocalDate.now();

        WorkoutRecord workout = WorkoutRecord.builder()
                .userId(userId)
                .date(today)
                .exerciseId(request.getExerciseId())
                .exerciseName(getExerciseName(request.getExerciseId()))
                .duration(request.getDuration())
                .caloriesBurned(request.getCalories())
                .build();
        workoutRecordRepository.save(workout);

        // 更新今日记录
        DailyRecord record = dailyRecordRepository.findByUserIdAndDate(userId, today)
                .orElseGet(() -> createDefaultDailyRecord(userId, today));

        List<WorkoutRecord> todayWorkouts = workoutRecordRepository.findByUserIdAndDateBetween(userId, today, today);
        double totalBurn = todayWorkouts.stream()
                .mapToDouble(WorkoutRecord::getCaloriesBurned)
                .sum();
        record.setExerciseBurn(totalBurn);
        dailyRecordRepository.save(record);

        return ApiResponse.success("运动记录已添加");
    }

    public ApiResponse getWorkoutRecords(Long userId, String startDate, String endDate) {
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().minusDays(30);
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

        List<WorkoutRecord> records = workoutRecordRepository.findByUserIdAndDateBetween(userId, start, end);
        List<WorkoutRecordDTO> dtos = records.stream()
                .map(this::toWorkoutRecordDTO)
                .collect(Collectors.toList());

        return ApiResponse.success(dtos);
    }

    public ApiResponse getCustomFoods(Long userId) {
        List<CustomFood> foods = customFoodRepository.findByUserId(userId);
        List<CustomFoodDTO> dtos = foods.stream()
                .map(this::toCustomFoodDTO)
                .collect(Collectors.toList());

        return ApiResponse.success(dtos);
    }

    @Transactional
    public ApiResponse addCustomFood(Long userId, CustomFoodDTO dto) {
        CustomFood food = CustomFood.builder()
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
        CustomFood saved = customFoodRepository.save(food);
        dto.setId(saved.getId());

        return ApiResponse.success("自定义食物已添加", dto);
    }

    @Transactional
    public ApiResponse deleteCustomFood(Long userId, Long id) {
        CustomFood food = customFoodRepository.findById(id).orElse(null);
        if (food == null || !food.getUserId().equals(userId)) {
            return ApiResponse.error("食物不存在或无权限");
        }
        customFoodRepository.delete(food);
        return ApiResponse.success("自定义食物已删除");
    }

    public ApiResponse syncData(Long userId) {
        SyncDataDTO syncData = new SyncDataDTO();

        // Profile
        UserProfile profile = profileRepository.findByUserId(userId).orElse(null);
        if (profile != null) {
            ProfileDTO profileDTO = new ProfileDTO();
            profileDTO.setName(profile.getName());
            profileDTO.setGender(profile.getGender());
            profileDTO.setAge(profile.getAge());
            profileDTO.setHeight(profile.getHeight());
            profileDTO.setWeight(profile.getWeight());
            profileDTO.setBodyFat(profile.getBodyFat());
            profileDTO.setGoal(profile.getGoal());
            profileDTO.setActivityLevel(profile.getActivityLevel());
            syncData.setProfile(profileDTO);
        }

        // Today Record
        LocalDate today = LocalDate.now();
        DailyRecord todayRecord = dailyRecordRepository.findByUserIdAndDate(userId, today)
                .orElseGet(() -> createDefaultDailyRecord(userId, today));
        syncData.setTodayRecord(toDailyRecordDTO(todayRecord));

        // Custom Foods
        List<CustomFood> foods = customFoodRepository.findByUserId(userId);
        syncData.setCustomFoods(foods.stream()
                .map(this::toCustomFoodDTO)
                .collect(Collectors.toList()));

        // Workout Records
        List<WorkoutRecord> workouts = workoutRecordRepository.findByUserId(userId);
        syncData.setWorkoutRecords(workouts.stream()
                .map(this::toWorkoutRecordDTO)
                .collect(Collectors.toList()));

        // Daily Records
        List<DailyRecord> records = dailyRecordRepository.findByUserId(userId);
        syncData.setDailyRecords(records.stream()
                .map(this::toDailyRecordDTO)
                .collect(Collectors.toList()));

        return ApiResponse.success(syncData);
    }

    private DailyRecord createDefaultDailyRecord(Long userId, LocalDate date) {
        DailyRecord record = DailyRecord.builder()
                .userId(userId)
                .date(date)
                .foodIntake(0.0)
                .exerciseBurn(0.0)
                .targetCalories(1800.0)
                .weight(0.0)
                .build();
        return dailyRecordRepository.save(record);
    }

    private DailyRecordDTO toDailyRecordDTO(DailyRecord record) {
        DailyRecordDTO dto = new DailyRecordDTO();
        dto.setDate(record.getDate().toString());
        dto.setFoodIntake(record.getFoodIntake());
        dto.setExerciseBurn(record.getExerciseBurn());
        dto.setTargetCalories(record.getTargetCalories());
        dto.setWeight(record.getWeight());
        return dto;
    }

    private WorkoutRecordDTO toWorkoutRecordDTO(WorkoutRecord record) {
        WorkoutRecordDTO dto = new WorkoutRecordDTO();
        dto.setDate(record.getDate().toString());
        dto.setExerciseId(record.getExerciseId());
        dto.setExerciseName(record.getExerciseName());
        dto.setDuration(record.getDuration());
        dto.setCaloriesBurned(record.getCaloriesBurned());
        return dto;
    }

    private CustomFoodDTO toCustomFoodDTO(CustomFood food) {
        CustomFoodDTO dto = new CustomFoodDTO();
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

    private String getExerciseName(String exerciseId) {
        // 简单的运动名称映射，实际可从数据库查询
        return switch (exerciseId) {
            case "1" -> "跑步";
            case "2" -> "游泳";
            case "3" -> "骑行";
            case "4" -> "瑜伽";
            case "5" -> "力量训练";
            default -> "运动";
        };
    }
}
