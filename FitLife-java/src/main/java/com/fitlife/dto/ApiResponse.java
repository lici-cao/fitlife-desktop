package com.fitlife.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 统一响应体。
 *
 * <p>同时提供 code / success 两套字段：前端既有按 code === 200 判断的页面
 * （训练计划、动作库），也有按 data 直接取值的页面（AI 搭子），
 * 缺少 code 会导致「接口成功但页面不渲染」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse {
    /** 业务状态码：200 成功，400 参数/业务错误，500 服务异常 */
    private int code;
    private boolean success;
    private String message;
    private Object data;

    public static ApiResponse success(String message) {
        return new ApiResponse(200, true, message, null);
    }

    public static ApiResponse success(String message, Object data) {
        return new ApiResponse(200, true, message, data);
    }

    public static ApiResponse success(Object data) {
        return new ApiResponse(200, true, "success", data);
    }

    public static ApiResponse error(String message) {
        return new ApiResponse(400, false, message, null);
    }

    public static ApiResponse error(String message, Object data) {
        return new ApiResponse(400, false, message, data);
    }

    public static ApiResponse serverError(String message) {
        return new ApiResponse(500, false, message, null);
    }
}
