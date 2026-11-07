package com.fitlife.dto;

import lombok.Data;

@Data
public class ExerciseSuggestionRequest {
    private String goal;
    private String activityLevel;
    private Integer availableMinutes;
}