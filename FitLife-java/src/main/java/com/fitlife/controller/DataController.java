package com.fitlife.controller;

import com.fitlife.common.BusinessException;
import com.fitlife.common.annotation.Idempotent;
import com.fitlife.common.annotation.OwnershipCheck;
import com.fitlife.dto.*;
import com.fitlife.entity.User;
import com.fitlife.entity.WorkoutRecord;
import com.fitlife.service.CheckInService;
import com.fitlife.service.DataService;
import com.fitlife.service.ProgressService;
import com.fitlife.service.StatsService;
import com.fitlife.service.TodayPlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 体质档案 / 饮食 / 运动 / 自定义食物接口。
 *
 * <p>P1 改动：三类「写入当日记录」的 POST 加 {@link Idempotent}（防止网络重试或双击
 * 造成重复热量累加）；自定义食物删除加 {@link OwnershipCheck}（原先只在 Service 里按
 * userId 查，删除他人 id 会静默失败，现在明确 403）。
 */
@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
public class DataController {

    private final DataService dataService;
    private final TodayPlanService todayPlanService;
    private final CheckInService checkInService;
    private final StatsService statsService;
    private final ProgressService progressService;

    private Long userId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof User user) || user.getId() == null) {
            throw new BusinessException(401, "未登录或登录已过期");
        }
        return user.getId();
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse> getProfile(Authentication auth) {
        return ResponseEntity.ok(dataService.getProfile(userId(auth)));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse> updateProfile(
            Authentication auth,
            @Valid @RequestBody ProfileDTO profileDTO) {
        return ResponseEntity.ok(dataService.updateProfile(userId(auth), profileDTO));
    }

    @GetMapping("/daily-record/today")
    public ResponseEntity<ApiResponse> getTodayRecord(Authentication auth) {
        return ResponseEntity.ok(dataService.getTodayRecord(userId(auth)));
    }

    @GetMapping("/daily-records")
    public ResponseEntity<ApiResponse> getDailyRecords(
            Authentication auth,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return ResponseEntity.ok(dataService.getDailyRecords(userId(auth), startDate, endDate));
    }

    @PostMapping("/food-intake")
    @Idempotent
    public ResponseEntity<ApiResponse> addFoodIntake(
            Authentication auth,
            @Valid @RequestBody FoodIntakeRequest request) {
        return ResponseEntity.ok(dataService.addFoodIntake(userId(auth), request));
    }

    @PostMapping("/exercise-burn")
    @Idempotent
    public ResponseEntity<ApiResponse> addExerciseBurn(
            Authentication auth,
            @Valid @RequestBody ExerciseBurnRequest request) {
        return ResponseEntity.ok(dataService.addExerciseBurn(userId(auth), request));
    }

    @GetMapping("/workout-records")
    public ResponseEntity<ApiResponse> getWorkoutRecords(
            Authentication auth,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return ResponseEntity.ok(dataService.getWorkoutRecords(userId(auth), startDate, endDate));
    }

    @GetMapping("/custom-foods")
    public ResponseEntity<ApiResponse> getCustomFoods(Authentication auth) {
        return ResponseEntity.ok(dataService.getCustomFoods(userId(auth)));
    }

    @PostMapping("/custom-foods")
    @Idempotent
    public ResponseEntity<ApiResponse> addCustomFood(
            Authentication auth,
            @Valid @RequestBody CustomFoodDTO dto) {
        return ResponseEntity.ok(dataService.addCustomFood(userId(auth), dto));
    }

    @DeleteMapping("/custom-foods/{id}")
    @OwnershipCheck(type = OwnershipCheck.ResourceType.CUSTOM_FOOD, idParam = "id")
    public ResponseEntity<ApiResponse> deleteCustomFood(
            Authentication auth,
            @PathVariable Long id) {
        return ResponseEntity.ok(dataService.deleteCustomFood(userId(auth), id));
    }

    @GetMapping("/sync")
    public ResponseEntity<ApiResponse> syncData(Authentication auth) {
        return ResponseEntity.ok(dataService.syncData(userId(auth)));
    }

    // ============================================================
    // P4：执行闭环（今日训练卡 / 打卡 / 统计 / 反馈决策）
    // ============================================================

    /** 今日训练卡：动作 + 组次 + 靶心率区间 + 禁忌提示 + 打卡状态 */
    @GetMapping("/today-plan")
    public ResponseEntity<ApiResponse> todayPlan(
            Authentication auth,
            @RequestParam(required = false) String date) {
        return ResponseEntity.ok(ApiResponse.success(todayPlanService.today(userId(auth), date)));
    }

    /**
     * 计划内打卡（完成 / 部分完成 / 跳过）。
     * 幂等：同一 Idempotency-Key 重放返回首次结果，不重复累加训练量与热量。
     */
    @PostMapping("/check-in")
    @Idempotent
    public ResponseEntity<ApiResponse> checkIn(
            Authentication auth,
            @Valid @RequestBody CheckInRequest request) {
        WorkoutRecord record = checkInService.checkIn(userId(auth), request);
        return ResponseEntity.ok(ApiResponse.success("打卡已记录", record));
    }

    /** 撤销某动作当天的打卡 */
    @DeleteMapping("/check-in")
    public ResponseEntity<ApiResponse> undoCheckIn(
            Authentication auth,
            @RequestParam Long planDetailId,
            @RequestParam(required = false) String date) {
        boolean removed = checkInService.undo(userId(auth), planDetailId, date);
        return ResponseEntity.ok(removed
                ? ApiResponse.success("已撤销打卡")
                : ApiResponse.success("当天没有该动作的打卡记录"));
    }

    /** 依从率 / 训练量 / 热量收支 / RPE 偏差 + 风险分级 */
    @GetMapping("/stats/summary")
    public ResponseEntity<ApiResponse> statsSummary(
            Authentication auth,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) Integer days) {
        return ResponseEntity.ok(ApiResponse.success(
                statsService.summary(userId(auth), endDate, days)));
    }

    /** 调整决策：HOLD / DELOAD / PROGRESS / SWAP / STOP + 理由与建议 */
    @GetMapping("/stats/advice")
    public ResponseEntity<ApiResponse> statsAdvice(
            Authentication auth,
            @RequestParam(required = false) Integer days) {
        return ResponseEntity.ok(ApiResponse.success(progressService.advice(userId(auth), days)));
    }

    /** 一键进阶：满足条件时生成下一版（≤10% 增幅，仍过校验器） */
    @PostMapping("/stats/advance")
    @Idempotent
    public ResponseEntity<ApiResponse> statsAdvance(
            Authentication auth,
            @RequestParam(required = false) Integer days) {
        return ResponseEntity.ok(ApiResponse.success(
                "进阶处理完成", progressService.advance(userId(auth), days)));
    }
}