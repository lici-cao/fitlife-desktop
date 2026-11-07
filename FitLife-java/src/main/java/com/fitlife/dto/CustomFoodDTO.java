package com.fitlife.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CustomFoodDTO {
    private Long id;

    @NotBlank(message = "食物名称不能为空")
    private String name;

    @NotNull(message = "热量不能为空")
    private Double calories;

    private Double protein;
    private Double carbs;
    private Double fat;
    private Double servingSize;
    private String unit;
    private String category;
}
