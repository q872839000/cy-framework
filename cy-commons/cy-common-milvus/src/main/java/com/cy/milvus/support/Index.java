package com.cy.milvus.support;

import io.milvus.param.IndexType;
import io.milvus.param.MetricType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @Auther: pcy
 * @Date: 2025/9/29 11:09
 * @Description: 索引声明
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Index {
    IndexType indexType() default IndexType.HNSW;
    String params() default ""; // JSON 或 key1=val1;key2=val2 等
    MetricType metricType() default MetricType.L2;
}