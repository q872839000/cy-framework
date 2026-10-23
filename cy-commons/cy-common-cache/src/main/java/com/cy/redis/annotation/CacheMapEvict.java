package com.cy.redis.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Map缓存删除注解
 * 用于在方法执行后删除Redis Hash结构中的值
 * 示例: @CacheMapEvict(mapKey = "sys:user:names", hashKey = "#{#id}")
 *
 * @author pcy
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheMapEvict {

    /**
     * Map的key（Redis Hash的key）
     * 支持SpEL表达式
     */
    String mapKey();

    /**
     * Hash中的field（Map中的key）
     * 支持SpEL表达式，如: "#{#id}" 或 "#{#result.id}"
     */
    String hashKey();

    /**
     * 执行条件，支持SpEL表达式
     * 例如: "#result == true"
     */
    String condition() default "";

    /**
     * 是否清除整个Map
     */
    boolean allEntries() default false;
}
