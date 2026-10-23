package com.cy.redis.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 缓存清除注解
 * 用于清除指定的缓存
 *
 * @author pcy
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheEvict {

    /**
     * 要清除的缓存key，支持SpEL表达式
     * 例如: "user:#{#userId}" 或 "knowledge:*"
     */
    String[] keys() default {};

    /**
     * 缓存 key 前缀
     */
    String keyPrefix() default "";

    /**
     * 是否清除所有以 keyPrefix 开头的缓存
     */
    boolean allEntries() default false;

    /**
     * 清除条件，支持SpEL表达式
     */
    String condition() default "";

    /**
     * 是否在方法执行前清除缓存
     */
    boolean beforeInvocation() default false;
}
