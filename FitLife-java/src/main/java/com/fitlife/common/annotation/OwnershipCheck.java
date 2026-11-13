package com.fitlife.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据归属校验（水平越权防护）。
 *
 * <p>加在「按资源 id 访问」的接口上。切面会在进入方法体之前，
 * 用当前登录用户的 id 去查询该资源是否属于本人：
 * 不属于则直接 403 并写一条审计日志，而不是先查出来再判断
 * （后者一旦某个分支漏判，就是越权读取）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OwnershipCheck {

    /** 资源类型，决定用哪个 Repository 做归属判断 */
    ResourceType type();

    /** 资源 id 所在的方法参数名，缺省 id；参数名不可用时退化为「第一个 Long 参数」 */
    String idParam() default "id";

    enum ResourceType {
        PLAN,
        CUSTOM_FOOD
    }
}