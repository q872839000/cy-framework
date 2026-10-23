package com.cy.milvus.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author pcy
 * @date 2026/01/19
 * @description BM25 函数注解，用于自动从文本字段生成稀疏向量
 * <p>
 * 使用方式：在稀疏向量字段上标注，指定输入的文本字段名称
 * </p>
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface BM25Function {
    
    /** BM25 函数名称 */
    String name();
    
    /** 输入文本字段名称（对应 @TextField 标注的字段） */
    String inputField();
}
