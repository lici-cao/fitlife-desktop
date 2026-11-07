package com.fitlife.controller;

import com.fitlife.dto.ApiResponse;
import com.fitlife.dto.ExerciseDTO;
import com.fitlife.entity.Exercise;
import com.fitlife.repository.ExerciseRepository;
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
                .build();
    }
}
