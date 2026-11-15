package com.fitlife.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等标记。
 *
 * <p>加在「会产生副作用的写接口」上，客户端通过请求头 {@code Idempotency-Key}
 * 声明一次逻辑提交。配合 {@code IdempotencyAspect}：
 * <ul>
 *   <li>同一个 key + 同一份请求体：不再执行，直接回放第一次的响应（响应头 {@code Idempotent-Replay: true}）；</li>
 *   <li>同一个 key + 不同请求体：409，避免「复用了 key 却改了参数」；</li>
 *   <li>第一次仍在处理中：409，前端据此提示「请求处理中」；</li>
 *   <li>请求头缺省：为了兼容旧前端，默认放行（{@link #required()} = true 时返回 400）。</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * 幂等窗口，单位秒。窗口内同一个 key 视为同一次提交；
     * 超过窗口（或记录处于「处理中」超过 {@code staleSeconds}）后允许重新认领。
     */
    int ttlSeconds() default 86400;

    /**
     * 「处理中」记录被视为僵死的时间（秒）。进程崩溃后残留的 status=0 记录，
     * 超过该时间可以重新认领，避免客户端被永久 409。
     */
    int staleSeconds() default 300;

    /** 是否强制要求携带 Idempotency-Key（默认 false，兼容未改造的旧前端） */
    boolean required() default false;
}