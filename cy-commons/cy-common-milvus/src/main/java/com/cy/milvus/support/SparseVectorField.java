package com.cy.milvus.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author pcy
 * @date 2026/01/19
 * @description 稀疏向量字段注解（用于 BM25 等稀疏检索）
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SparseVectorField {
    
    /** 字段名称 */
    String name() default "";
    
    /** 描述 */
    String description() default "";
    
    /** 是否在 schema 中声明（默认 true） */
    boolean declare() default true;
}
