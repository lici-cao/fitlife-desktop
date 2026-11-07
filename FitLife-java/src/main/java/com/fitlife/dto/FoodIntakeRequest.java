package com.fitlife.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FoodIntakeRequest {
    @NotNull(message = "热量不能为空")
    private Double calories;

    private String foodId;
    private Double amount;
}
