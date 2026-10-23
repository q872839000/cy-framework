package com.cy.redis.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Map缓存更新注解
 * 用于在方法执行后更新Redis Hash结构中的值
 * 示例: @CacheMapPut(mapKey = "sys:user:names", hashKey = "#{#user.id}", value = "#{#user.nickname}")
 *
 * @author pcy
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheMapPut {

    /**
     * Map的key（Redis Hash的key）
     * 支持SpEL表达式，如: "sys:user:names" 或 "#{T(com.cy.core.constants.Constants).USER_CACHE_KEY}"
     */
    String mapKey();

    /**
     * Hash中的field（Map中的key）
     * 支持SpEL表达式，如: "#{#user.id}" 或 "#{#result.id}"
     */
    String hashKey();

    /**
     * 要存储的值
     * 支持SpEL表达式，如: "#{#user.nickname}" 或 "#{#result.nickname}"
     */
    String value();

    /**
     * 执行条件，支持SpEL表达式
     * 例如: "#result == true" 或 "#result != null"
     */
    String condition() default "";
}
