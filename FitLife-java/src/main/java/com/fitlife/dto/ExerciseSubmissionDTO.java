package com.fitlife.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 动作投稿请求体（P5）。字段与 exercises 一一对应，另加来源信息。
 * 校验分两级：Bean Validation 保证格式，ExerciseSubmissionService 保证业务门槛。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExerciseSubmissionDTO {

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

    /** GUIDELINE / TEXTBOOK / JOURNAL / DATABASE / COMMUNITY */
    private String sourceType;
    private String sourceTitle;
    private String sourceUrl;
    private String sourcePage;
    /** A / B / C */
    private String evidenceLevel;
    private String licenseNote;
}