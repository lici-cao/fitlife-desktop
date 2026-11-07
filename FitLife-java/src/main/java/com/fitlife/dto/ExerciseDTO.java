package com.fitlife.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 动作库对外展示对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExerciseDTO {
    private Long id;
    private String name;
    private String category;
    private String muscleGroup;
    private String equipment;
    private Integer difficulty;
    private Double metValue;
    private Integer defaultSets;
    private String defaultReps;
    private String description;
    private String contraindication;
}
