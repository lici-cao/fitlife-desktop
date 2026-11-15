package com.fitlife.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体（P1 收口）。
 *
 * <p>四个字段的语义固定下来，前端不必再「有的页面看 code、有的页面看 data」：
 * <ul>
 *   <li>{@code code}：业务状态码，直接取 HTTP 语义（200/400/401/403/404/409/429/500/503）；</li>
 *   <li>{@code success}：恒等于 {@code code < 400}，由构造器维护，避免手工传入不一致的值；</li>
 *   <li>{@code message}：给人看的中文提示，不包含堆栈、SQL、上游厂商原文；</li>
 *   <li>{@code data}：业务数据；失败时可为校验错误明细或 null。</li>
 * </ul>
 *
 * <p>兼容性：保留了原来的 {@code success(...)} / {@code error(...)} / {@code serverError(...)}
 * 工厂方法签名，旧调用点无需改动即可编译。
 */
@Data
@NoArgsConstructor
public class ApiResponse {

    private int code;
    private boolean success;
    private String message;
    private Object data;

    public ApiResponse(int code, boolean success, String message, Object data) {
        this.code = code;
        this.message = message;
        this.data = data;
        // success 不再信任调用方，统一由 code 推导
        this.success = code < 400;
    }

    // ---------- 成功 ----------

    public static ApiResponse success() {
        return new ApiResponse(200, true, "success", null);
    }

    public static ApiResponse success(String message) {
        return new ApiResponse(200, true, message, null);
    }

    public static ApiResponse success(String message, Object data) {
        return new ApiResponse(200, true, message, data);
    }

    public static ApiResponse success(Object data) {
        return new ApiResponse(200, true, "success", data);
    }

    // ---------- 失败 ----------

    public static ApiResponse error(String message) {
        return new ApiResponse(400, false, message, null);
    }

    public static ApiResponse error(String message, Object data) {
        return new ApiResponse(400, false, message, data);
    }

    public static ApiResponse fail(int code, String message) {
        return new ApiResponse(code, false, message, null);
    }

    public static ApiResponse fail(int code, String message, Object data) {
        return new ApiResponse(code, false, message, data);
    }

    public static ApiResponse unauthorized(String message) {
        return new ApiResponse(401, false, message, null);
    }

    public static ApiResponse forbidden(String message) {
        return new ApiResponse(403, false, message, null);
    }

    public static ApiResponse notFound(String message) {
        return new ApiResponse(404, false, message, null);
    }

    public static ApiResponse conflict(String message) {
        return new ApiResponse(409, false, message, null);
    }

    public static ApiResponse serverError(String message) {
        return new ApiResponse(500, false, message, null);
    }
}