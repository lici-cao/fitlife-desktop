package com.fitlife.dto;

import lombok.Data;

@Data
public class DailyRecordDTO {
    private String date;
    private Double foodIntake;
    private Double exerciseBurn;
    private Double targetCalories;
    private Double weight;
}
