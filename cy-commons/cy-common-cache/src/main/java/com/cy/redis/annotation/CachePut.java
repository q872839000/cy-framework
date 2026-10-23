package com.cy.redis.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 缓存更新注解
 * 用于强制更新缓存，无论缓存是否存在都会执行方法并更新缓存
 *
 * @author pcy
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CachePut {

    /**
     * 缓存key，支持SpEL表达式
     * 例如: "user:#{#userId}" 或 "knowledge:#{#id}"
     */
    String key();

    /**
     * 缓存过期时间
     */
    long expire() default 300;

    /**
     * 时间单位
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 缓存条件，支持SpEL表达式
     * 例如: "#result != null"
     */
    String condition() default "";

    /**
     * 是否允许缓存null值
     */
    boolean cacheNull() default false;

    /**
     * 缓存key前缀
     */
    String keyPrefix() default "";
}
