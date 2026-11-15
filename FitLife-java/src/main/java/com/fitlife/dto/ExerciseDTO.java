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

    // ===== P5 寻源：动作库每条都能点开出处 =====

    private String sourceType;
    private String sourceTitle;
    private String sourceUrl;
    private String sourcePage;
    private String evidenceLevel;
    private String licenseNote;
    private String reviewStatus;
    /** 来源链接最近核验日期 */
    private java.time.LocalDate lastVerifiedAt;
}
