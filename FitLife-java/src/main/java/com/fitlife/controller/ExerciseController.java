package com.fitlife.controller;

import com.fitlife.dto.ApiResponse;
import com.fitlife.dto.ExerciseDTO;
import com.fitlife.dto.ExerciseSubmissionDTO;
import com.fitlife.entity.Exercise;
import com.fitlife.entity.ExerciseSubmission;
import com.fitlife.entity.User;
import com.fitlife.repository.ExerciseRepository;
import com.fitlife.service.ExerciseSubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 动作库查询接口：供计划页展示动作要领，也便于答辩演示“知识库”这一层
 */
@RestController
@RequestMapping("/api/exercise")
@RequiredArgsConstructor
public class ExerciseController {

    private final ExerciseRepository exerciseRepository;
    private final ExerciseSubmissionService exerciseSubmissionService;

    /** 动作列表，可按类别/肌群/器械筛选 */
    @GetMapping("/list")
    public ResponseEntity<ApiResponse> list(@RequestParam(required = false) String category,
                                            @RequestParam(required = false) String muscleGroup,
                                            @RequestParam(required = false) String equipment,
                                            @RequestParam(required = false) Integer maxDifficulty) {
        List<Exercise> all = exerciseRepository.findAll();
        List<ExerciseDTO> list = all.stream()
                .filter(e -> category == null || category.isBlank() || category.equals(e.getCategory()))
                .filter(e -> muscleGroup == null || muscleGroup.isBlank() || muscleGroup.equals(e.getMuscleGroup()))
                .filter(e -> equipment == null || equipment.isBlank() || equipment.equals(e.getEquipment()))
                .filter(e -> maxDifficulty == null || e.getDifficulty() <= maxDifficulty)
                .map(this::toDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    /** 动作库统计：类别、肌群、器械分布 */
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse> stats() {
        List<Exercise> all = exerciseRepository.findAll();
        Map<String, Long> byCategory = all.stream()
                .collect(Collectors.groupingBy(Exercise::getCategory, Collectors.counting()));
        Map<String, Long> byMuscle = all.stream()
                .collect(Collectors.groupingBy(Exercise::getMuscleGroup, Collectors.counting()));
        Set<String> equipments = all.stream()
                .map(Exercise::getEquipment)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Object> result = Map.of(
                "total", all.size(),
                "byCategory", byCategory,
                "byMuscleGroup", byMuscle,
                "equipments", equipments
        );
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** 单个动作详情 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse> detail(@PathVariable Long id) {
        return exerciseRepository.findById(id)
                .map(e -> ResponseEntity.ok(ApiResponse.success(toDTO(e))))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.error("动作不存在")));
    }

    // ============================================================
    // P5 寻源与投稿
    // ============================================================

    /** 动作库来源统计：多少条带链接、证据等级分布、待审投稿数 */
    @GetMapping("/sources/stats")
    public ResponseEntity<ApiResponse> sourceStats() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("total", exerciseRepository.count());
        result.put("withoutSource", exerciseRepository.countWithoutSource());
        result.put("evidenceA", exerciseRepository.countByEvidenceLevel("A"));
        result.put("evidenceB", exerciseRepository.countByEvidenceLevel("B"));
        result.put("evidenceC", exerciseRepository.countByEvidenceLevel("C"));
        result.put("pendingSubmissions", exerciseSubmissionService.pendingCount());
        result.put("policy", "每条动作都需给出可访问的来源链接；投稿经机器门槛 + 人工复核后才入动作库，"
                + "审核核的是「来源可查、描述与来源一致、MET/难度量级相符」，不替第三方背书。");
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** 用户投稿新动作（落 exercise_submission，不直接进动作库） */
    @PostMapping("/submit")
    public ResponseEntity<ApiResponse> submit(@RequestBody ExerciseSubmissionDTO dto,
                                              org.springframework.security.core.Authentication auth) {
        User user = (User) auth.getPrincipal();
        ExerciseSubmission sub = exerciseSubmissionService.submit(user.getId(), user.getUsername(), dto);
        ApiResponse body = ApiResponse.success(
                ExerciseSubmission.PENDING.equals(sub.getStatus())
                        ? "已提交，等待管理员审核" : "投稿未通过自动校验，请按提示补充后重投",
                submissionVO(sub));
        return ResponseEntity.ok(body);
    }

    /** 我的投稿记录（含驳回原因） */
    @GetMapping("/my-submissions")
    public ResponseEntity<ApiResponse> mySubmissions(org.springframework.security.core.Authentication auth) {
        User user = (User) auth.getPrincipal();
        List<Map<String, Object>> list = exerciseSubmissionService.mine(user.getId()).stream()
                .map(this::submissionVO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    private Map<String, Object> submissionVO(ExerciseSubmission s) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("category", s.getCategory());
        m.put("muscleGroup", s.getMuscleGroup());
        m.put("equipment", s.getEquipment());
        m.put("difficulty", s.getDifficulty());
        m.put("metValue", s.getMetValue());
        m.put("sourceType", s.getSourceType());
        m.put("sourceTitle", s.getSourceTitle());
        m.put("sourceUrl", s.getSourceUrl());
        m.put("evidenceLevel", s.getEvidenceLevel());
        m.put("status", s.getStatus());
        m.put("reviewNote", s.getReviewNote());
        m.put("createdExerciseId", s.getCreatedExerciseId());
        m.put("createdAt", s.getCreatedAt());
        m.put("reviewedAt", s.getReviewedAt());
        return m;
    }

    private ExerciseDTO toDTO(Exercise e) {
        return ExerciseDTO.builder()
                .id(e.getId())
                .name(e.getName())
                .category(e.getCategory())
                .muscleGroup(e.getMuscleGroup())
                .equipment(e.getEquipment())
                .difficulty(e.getDifficulty())
                .metValue(e.getMetValue())
                .defaultSets(e.getDefaultSets())
                .defaultReps(e.getDefaultReps())
                .description(e.getDescription())
                .contraindication(e.getContraindication())
                .sourceType(e.getSourceType())
                .sourceTitle(e.getSourceTitle())
                .sourceUrl(e.getSourceUrl())
                .sourcePage(e.getSourcePage())
                .evidenceLevel(e.getEvidenceLevel())
                .licenseNote(e.getLicenseNote())
                .reviewStatus(e.getReviewStatus())
                .lastVerifiedAt(e.getLastVerifiedAt())
                .build();
    }
}
