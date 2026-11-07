package com.fitlife.dto;

import lombok.Data;

@Data
public class DietSuggestionRequest {
    private Double currentWeight;
    private Double targetWeight;
    private String goal;
    private String activityLevel;
}