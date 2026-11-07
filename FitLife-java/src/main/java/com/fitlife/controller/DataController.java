package com.fitlife.controller;

import com.fitlife.dto.*;
import com.fitlife.entity.User;
import com.fitlife.service.DataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
public class DataController {

    private final DataService dataService;

    private Long getUserId(Authentication auth) {
        return ((User) auth.getPrincipal()).getId();
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse> getProfile(Authentication auth) {
        return ResponseEntity.ok(dataService.getProfile(getUserId(auth)));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse> updateProfile(
            Authentication auth,
            @Valid @RequestBody ProfileDTO profileDTO) {
        return ResponseEntity.ok(dataService.updateProfile(getUserId(auth), profileDTO));
    }

    @GetMapping("/daily-record/today")
    public ResponseEntity<ApiResponse> getTodayRecord(Authentication auth) {
        return ResponseEntity.ok(dataService.getTodayRecord(getUserId(auth)));
    }

    @GetMapping("/daily-records")
    public ResponseEntity<ApiResponse> getDailyRecords(
            Authentication auth,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return ResponseEntity.ok(dataService.getDailyRecords(getUserId(auth), startDate, endDate));
    }

    @PostMapping("/food-intake")
    public ResponseEntity<ApiResponse> addFoodIntake(
            Authentication auth,
            @Valid @RequestBody FoodIntakeRequest request) {
        return ResponseEntity.ok(dataService.addFoodIntake(getUserId(auth), request));
    }

    @PostMapping("/exercise-burn")
    public ResponseEntity<ApiResponse> addExerciseBurn(
            Authentication auth,
            @Valid @RequestBody ExerciseBurnRequest request) {
        return ResponseEntity.ok(dataService.addExerciseBurn(getUserId(auth), request));
    }

    @GetMapping("/workout-records")
    public ResponseEntity<ApiResponse> getWorkoutRecords(
            Authentication auth,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return ResponseEntity.ok(dataService.getWorkoutRecords(getUserId(auth), startDate, endDate));
    }

    @GetMapping("/custom-foods")
    public ResponseEntity<ApiResponse> getCustomFoods(Authentication auth) {
        return ResponseEntity.ok(dataService.getCustomFoods(getUserId(auth)));
    }

    @PostMapping("/custom-foods")
    public ResponseEntity<ApiResponse> addCustomFood(
            Authentication auth,
            @Valid @RequestBody CustomFoodDTO dto) {
        return ResponseEntity.ok(dataService.addCustomFood(getUserId(auth), dto));
    }

    @DeleteMapping("/custom-foods/{id}")
    public ResponseEntity<ApiResponse> deleteCustomFood(
            Authentication auth,
            @PathVariable Long id) {
        return ResponseEntity.ok(dataService.deleteCustomFood(getUserId(auth), id));
    }

    @GetMapping("/sync")
    public ResponseEntity<ApiResponse> syncData(Authentication auth) {
        return ResponseEntity.ok(dataService.syncData(getUserId(auth)));
    }
}
