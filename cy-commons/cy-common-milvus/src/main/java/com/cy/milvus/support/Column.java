package com.cy.milvus.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @Auther: pcy
 * @Date: 2025/9/29 11:08
 * @Description: 通用字段注解
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Column {
    String name() default "";
    /** VarChar 最大长度 (只对 VarChar 有效) */
    int maxLength() default 1024;
    /** 是否作为主键 */
    boolean primaryKey() default false;
    /** 主键是否 autoId */
    boolean autoId() default false;
    /** 是否把该字段放入 $meta 而不是显式 schema */
    boolean asMeta() default false;
    /** 字段描述 */
    String description() default "";
    /** 是否允许为空（默认 false） */
    boolean nullable() default false;
    /** 是否创建索引（标量字段索引，用于过滤加速） */
    boolean index() default false;
}
