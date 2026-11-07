package com.fitlife.dto;

import lombok.Data;

import java.util.List;

@Data
public class PredictWeightResponse {
    private String prediction;
    private List<Double> weightTrend;
}