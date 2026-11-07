package com.fitlife.dto;

import lombok.Data;

import java.util.List;

@Data
public class AiChatResponse {
    private String reply;

    /** AI 回复中引用到的动作库动作（RAG：只推荐库内真实动作） */
    private List<ExerciseDTO> matchedExercises;

    /** 提示前端可执行的操作，如 GO_PLAN / GO_MATCH */
    private String action;
}