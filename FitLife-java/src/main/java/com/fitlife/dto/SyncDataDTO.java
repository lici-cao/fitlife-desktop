package com.fitlife.dto;

import lombok.Data;
import java.util.List;

@Data
public class SyncDataDTO {
    private ProfileDTO profile;
    private DailyRecordDTO todayRecord;
    private List<CustomFoodDTO> customFoods;
    private List<WorkoutRecordDTO> workoutRecords;
    private List<DailyRecordDTO> dailyRecords;
}
