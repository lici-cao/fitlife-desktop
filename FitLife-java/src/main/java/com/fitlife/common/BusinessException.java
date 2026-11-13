package com.fitlife.common;

/**
 * 业务异常。
 *
 * <p>P1 统一响应体的核心约定：业务代码只抛「可预期的失败」，由
 * {@code GlobalExceptionHandler} 统一翻译成 {@code ApiResponse}；
 * 不再由 Controller 自己 catch RuntimeException 后把 e.getMessage() 原样回传，
 * 从而避免把上游供应商错误、SQL 片段、内部类名泄露给前端。
 */
public class BusinessException extends RuntimeException {

    /** 业务状态码：400 参数/业务错误，401 未认证，403 越权，404 不存在，409 冲突，429 限流，503 依赖不可用 */
    private final int code;

    /** 可选的结构化附加信息（如字段级错误），会放进 ApiResponse.data */
    private final Object data;

    public BusinessException(String message) {
        this(400, message, null);
    }

    public BusinessException(int code, String message) {
        this(code, message, null);
    }

    public BusinessException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    public Object getData() {
        return data;
    }
}