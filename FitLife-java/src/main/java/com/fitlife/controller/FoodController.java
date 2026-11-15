package com.fitlife.controller;

import com.fitlife.dto.ApiResponse;
import com.fitlife.entity.FoodItem;
import com.fitlife.repository.FoodItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 食物成分库（P5 饮食方案的数据源）。
 *
 * <p>只读接口。返回字段刻意保留 kcalPer100g 与 unitLabel/gramsPerUnit，
 * 让前端能同时显示「每 100g 多少 kcal」和「一份是多少克、多少 kcal」，
 * 避免用户把 100g 误当作一份。每条都带 sourceUrl，可点开成分表核对。
 */
@RestController
@RequestMapping("/api/food")
@RequiredArgsConstructor
public class FoodController {

    private final FoodItemRepository foodItemRepository;

    @GetMapping("/list")
    public ResponseEntity<ApiResponse> list(@RequestParam(required = false) String category,
                                            @RequestParam(required = false) String keyword) {
        List<FoodItem> items;
        if (keyword != null && !keyword.isBlank()) {
            items = foodItemRepository.findByNameContainingAndEnabledOrderByIdAsc(keyword.trim(), 1);
        } else if (category != null && !category.isBlank()) {
            items = foodItemRepository.findByCategoryAndEnabledOrderByIdAsc(category.trim(), 1);
        } else {
            items = foodItemRepository.findByEnabledOrderByCategoryAscIdAsc(1);
        }
        return ResponseEntity.ok(ApiResponse.success(items.stream().map(this::toVO).collect(Collectors.toList())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse> detail(@PathVariable Long id) {
        return foodItemRepository.findById(id)
                .map(f -> ResponseEntity.ok(ApiResponse.success(toVO(f))))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.error("食物条目不存在")));
    }

    /** 分类清单，供前端做筛选标签 */
    @GetMapping("/categories")
    public ResponseEntity<ApiResponse> categories() {
        List<FoodItem> all = foodItemRepository.findByEnabledOrderByCategoryAscIdAsc(1);
        Map<String, Long> counts = all.stream()
                .filter(f -> f.getCategory() != null)
                .collect(Collectors.groupingBy(FoodItem::getCategory, LinkedHashMap::new, Collectors.counting()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", all.size());
        result.put("categories", counts);
        result.put("unit", "热量统一为 kcal/100g 可食部；gramsPerUnit 表示 1 个计量单位折合克数");
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private Map<String, Object> toVO(FoodItem f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("name", f.getName());
        m.put("alias", f.getAlias());
        m.put("category", f.getCategory());
        m.put("kcalPer100g", f.getKcalPer100g());
        m.put("proteinG", f.getProteinG());
        m.put("carbG", f.getCarbG());
        m.put("fatG", f.getFatG());
        m.put("fiberG", f.getFiberG());
        m.put("unitLabel", f.getUnitLabel());
        m.put("householdMeasure", f.getHouseholdMeasure());
        m.put("gramsPerUnit", f.getGramsPerUnit());
        // 一份的实际热量，直接给前端用，避免各端各算一遍
        if (f.getGramsPerUnit() != null && f.getKcalPer100g() != null) {
            m.put("kcalPerUnit", Math.round(f.getKcalPer100g() * f.getGramsPerUnit() / 100.0 * 10) / 10.0);
        }
        m.put("sourceCode", f.getSourceCode());
        m.put("sourceTitle", f.getSourceTitle());
        m.put("sourceUrl", f.getSourceUrl());
        m.put("evidenceLevel", f.getEvidenceLevel());
        m.put("verified", f.getVerified());
        return m;
    }
}