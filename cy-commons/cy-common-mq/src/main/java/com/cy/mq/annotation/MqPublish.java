package com.cy.mq.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MQ 发布注解
 * <p>
 * 标记在方法上，通过 AOP 在方法执行完成后自动发布消息。
 * </p>
 * <p>
 * 默认发布方法返回值（可通过 {@link #payload()} 指定发布内容）。
 * </p>
 *
 * @author pcy
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MqPublish {

    /**
     * Topic
     * <p>
     * 支持 SpEL 表达式占位符：<code>"order-#{#p0.type}"</code>、<code>"#{#result.topic}"</code>
     * </p>
     */
    String topic();

    /**
     * 驱动类型
     */
    String driver() default "";

    /**
     * 消息 Key
     * <p>
     * 支持表达式占位符：<code>"order:#{#p0.id}"</code>
     * </p>
     */
    String key() default "";

    /**
     * 发布的消息体表达式
     * <p>
     * 支持 SpEL 表达式占位符：<code>"#{#result}"</code>、<code>"#{#p0}"</code>。
     * </p>
     * <p>
     * 为空表示发布方法返回值。
     * </p>
     */
    String payload() default "";

    /**
     * 是否允许发布 null
     */
    boolean publishNull() default false;
}
