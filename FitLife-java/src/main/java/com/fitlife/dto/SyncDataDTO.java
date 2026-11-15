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

    /** P4：今日训练卡摘要（无计划时为 null），供客户端一次初始化 */
    private TodayPlanVO todayPlan;
}
