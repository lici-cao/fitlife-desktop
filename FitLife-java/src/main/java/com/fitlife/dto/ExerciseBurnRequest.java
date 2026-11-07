package com.fitlife.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ExerciseBurnRequest {
    @NotNull(message = "消耗热量不能为空")
    private Double calories;

    @NotBlank(message = "运动项目ID不能为空")
    private String exerciseId;

    @NotNull(message = "运动时长不能为空")
    private Integer duration;
}
