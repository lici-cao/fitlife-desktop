package com.fitlife.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PredictWeightRequest {
    @NotNull(message = "当前体重不能为空")
    private Double currentWeight;

    @NotNull(message = "目标体重不能为空")
    private Double targetWeight;

    private Integer days;
}