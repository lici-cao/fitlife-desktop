package com.fitlife.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.entity.FoodItem;
import com.fitlife.repository.FoodItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 食物库种子补齐（5.0 桌面版）。
 *
 * <p>上游 5.0 只有动作库种子（db/exercises.json），food_item 表没有任何初始数据，
 * 而 {@link com.fitlife.service.DietPlanner} 严格依赖 food_item 表按分类（主食/蛋白/蔬菜/水果/乳品）
 * 取数：表为空时它直接返回空列表，进而 PlanOrchestrator 把 {@code dietEnabled} 置 false，
 * 饮食方案功能在全新安装环境下等于不可用。桌面版要求「双击即用」，因此这里在启动时
 * 按名称去重补入内置成分表种子，行为与 ExerciseSeedInitializer 保持一致：
 * 只补缺失、不覆盖用户已有数据，异常不影响启动。
 */
@Component
@Order(11)
@RequiredArgsConstructor
@Slf4j
public class FoodSeedInitializer implements ApplicationRunner {

    private static final String SEED_PATH = "db/foods.json";

    private final FoodItemRepository foodItemRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            ClassPathResource resource = new ClassPathResource(SEED_PATH);
            if (!resource.exists()) {
                log.warn("未找到食物种子 {}，跳过食物库补齐", SEED_PATH);
                return;
            }
            List<Map<String, Object>> seeds;
            try (InputStream in = resource.getInputStream()) {
                seeds = objectMapper.readValue(in, new TypeReference<List<Map<String, Object>>>() {
                });
            }

            List<FoodItem> existing = foodItemRepository.findAll();
            Set<String> names = new HashSet<>();
            for (FoodItem f : existing) {
                if (f.getName() != null) {
                    names.add(f.getName());
                }
            }
            int base = existing.size();

            List<FoodItem> toAdd = new ArrayList<>();
            for (Map<String, Object> m : seeds) {
                String name = str(m.get("name"));
                if (name.isEmpty() || !names.add(name)) {
                    continue;
                }
                String category = str(m.get("category"));
                if (category.isEmpty()) {
                    continue;
                }
                double kcal = dbl(m.get("kcalPer100g"), -1);
                if (kcal <= 0) {
                    continue;
                }
                String unitLabel = str(m.get("unitLabel"));
                toAdd.add(FoodItem.builder()
                        .name(name)
                        .alias(str(m.get("alias")))
                        .category(category)
                        .kcalPer100g(kcal)
                        .proteinG(dbl(m.get("proteinG"), 0))
                        .carbG(dbl(m.get("carbG"), 0))
                        .fatG(dbl(m.get("fatG"), 0))
                        .fiberG(dbl(m.get("fiberG"), 0))
                        .unitLabel(unitLabel.isEmpty() ? "g" : unitLabel)
                        .householdMeasure(str(m.get("householdMeasure")))
                        .gramsPerUnit(dblOrNull(m.get("gramsPerUnit")))
                        .sourceCode(str(m.get("sourceCode")))
                        .sourceTitle(str(m.get("sourceTitle")))
                        .sourceUrl(str(m.get("sourceUrl")))
                        .evidenceLevel(str(m.get("evidenceLevel")))
                        .verified(1)
                        .enabled(1)
                        .build());
            }

            if (toAdd.isEmpty()) {
                log.info("食物库已就绪，共 {} 条食物", base);
                return;
            }
            foodItemRepository.saveAll(toAdd);
            log.info("食物库自动补齐 {} 条食物，当前共 {} 条", toAdd.size(), base + toAdd.size());
        } catch (Exception e) {
            log.warn("食物库自动补齐失败，忽略：{}", e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static double dbl(Object o, double fallback) {
        if (o == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Double dblOrNull(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
