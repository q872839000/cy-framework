package com.cy.redis.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 缓存结果注解
 * 用于方法返回值缓存
 *
 * @author pcy
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheResult {

    /**
     * 缓存key，支持SpEL表达式
     * 例如: "user:#{#userId}"
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
     * 例如: "#result != null && #result.size() > 0"
     */
    String condition() default "";

    /**
     * 是否允许缓存 null值
     */
    boolean cacheNull() default false;

    /**
     * 缓存 key前缀
     */
    String keyPrefix() default "";
}
