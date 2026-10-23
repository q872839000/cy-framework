package com.cy.milvus.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author pcy
 * @date 2026/01/19
 * @description 文本字段注解，支持全文检索（BM25）
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TextField {
    
    /** 字段名称 */
    String name() default "";
    
    /** VarChar 最大长度 */
    int maxLength() default 65535;
    
    /** 是否启用分析器（用于 BM25 检索） */
    boolean enableAnalyzer() default true;
    
    /** 是否启用匹配功能 */
    boolean enableMatch() default true;
    
    /** 分析器类型：chinese, english, standard 等 */
    String analyzerType() default "chinese";
    
    /** 描述 */
    String description() default "";
}
