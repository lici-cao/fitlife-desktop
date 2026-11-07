package com.fitlife.dto;

import lombok.Data;

import java.util.List;

@Data
public class DietSuggestionResponse {
    private String summary;
    private List<String> recommendations;
}