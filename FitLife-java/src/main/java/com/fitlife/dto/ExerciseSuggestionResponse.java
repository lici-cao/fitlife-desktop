package com.fitlife.dto;

import lombok.Data;

import java.util.List;

@Data
public class ExerciseSuggestionResponse {
    private String summary;
    private List<String> recommendations;
}