package com.cy.milvus.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @Auther: pcy
 * @Date: 2025/9/29 11:06
 * @Description: 向量字段标注注解
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface VectorField {

    /** 字段名称 */
    String name() default "";
    /** 向量维度；如果为 -1 表示运行时推断 */
    int dimension() default -1;
    /** 描述 */
    String description() default "";
    /** 是否在 schema 中声明（默认 true） */
    boolean declare() default true;
}
