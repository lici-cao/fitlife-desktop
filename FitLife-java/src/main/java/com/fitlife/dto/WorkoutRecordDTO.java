package com.fitlife.dto;

import lombok.Data;

@Data
public class WorkoutRecordDTO {
    private String date;
    private String exerciseId;
    private String exerciseName;
    private Integer duration;
    private Double caloriesBurned;
}
