package com.fitlife.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.entity.Exercise;
import com.fitlife.repository.ExerciseRepository;
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
 * 动作库兜底初始化器。
 *
 * <p>解决「数据库里没有运动动作库」这一根因：即使没有手动执行 SQL 脚本，
 * 只要应用能连上数据库，启动时就会检查 exercises 表并补齐内置动作。
 *
 * <p>设计原则：
 * <ul>
 *   <li>只补齐缺失的动作名，绝不覆盖或删除用户已有数据；</li>
 *   <li>种子数据来自 classpath:db/exercises.json，与 SQL 脚本同源；</li>
 *   <li>任何异常都只记录日志，不阻断应用启动。</li>
 * </ul>
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class ExerciseSeedInitializer implements ApplicationRunner {

    private final ExerciseRepository exerciseRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            ClassPathResource resource = new ClassPathResource("db/exercises.json");
            if (!resource.exists()) {
                log.warn("未找到内置动作库种子文件 db/exercises.json，跳过自动初始化");
                return;
            }

            List<Map<String, Object>> seeds;
            try (InputStream in = resource.getInputStream()) {
                seeds = objectMapper.readValue(in, new TypeReference<List<Map<String, Object>>>() {});
            }

            List<Exercise> existing = exerciseRepository.findAll();
            Set<String> existingNames = new HashSet<>();
            for (Exercise e : existing) {
                existingNames.add(e.getName());
            }

            List<Exercise> toAdd = new ArrayList<>();
            for (Map<String, Object> row : seeds) {
                String name = str(row.get("name"));
                if (name.isEmpty() || existingNames.contains(name)) {
                    continue;
                }
                existingNames.add(name);
                toAdd.add(Exercise.builder()
                        .name(name)
                        .category(str(row.get("category")))
                        .muscleGroup(str(row.get("muscle_group")))
                        .equipment(str(row.get("equipment")))
                        .difficulty(intVal(row.get("difficulty"), 1))
                        .metValue(doubleVal(row.get("met_value"), 3.0))
                        .defaultSets(intVal(row.get("default_sets"), 3))
                        .defaultReps(str(row.get("default_reps")))
                        .description(str(row.get("description")))
                        .contraindication(str(row.get("contraindication")))
                        .build());
            }

            if (toAdd.isEmpty()) {
                log.info("动作库已就绪，共 {} 条动作", existing.size());
            } else {
                exerciseRepository.saveAll(toAdd);
                log.info("动作库自动补齐 {} 条动作，当前共 {} 条", toAdd.size(), existing.size() + toAdd.size());
            }
        } catch (Exception e) {
            // 数据库未就绪时不影响启动流程，后续由 SQL 脚本负责导入
            log.warn("动作库自动初始化未完成（可稍后执行 fitlife-mysql.sql）: {}", e.getMessage());
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private int intVal(Object o, int fallback) {
        try {
            return o == null ? fallback : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double doubleVal(Object o, double fallback) {
        try {
            return o == null ? fallback : Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
