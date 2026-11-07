package com.fitlife.dto;

import lombok.Data;

@Data
public class DailySummaryRequest {
    private Double foodIntake;
    private Double exerciseBurn;
    private Double targetCalories;
    private Double weight;
}