package com.cy.milvus.support;

import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import lombok.Getter;

import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2025/9/29 11:41
 * @Description: v2 API Schema 构建结果
 * 包含字段定义、索引参数和 BM25 函数定义
 */
@Getter
public class SchemaResult {

    /** 字段请求列表 */
    private final List<AddFieldReq> fieldReqs;

    /** 索引参数列表 */
    private final List<IndexParam> indexParams;

    /** BM25 函数列表 */
    private final List<CreateCollectionReq.Function> functions;

    public SchemaResult(List<AddFieldReq> fieldReqs,
                        List<IndexParam> indexParams,
                        List<CreateCollectionReq.Function> functions) {
        this.fieldReqs = fieldReqs;
        this.indexParams = indexParams;
        this.functions = functions;
    }

    /**
     * 将字段和函数定义应用到 schema
     * @param schema 目标 schema
     */
    public void applyToSchema(CreateCollectionReq.CollectionSchema schema) {
        // 添加字段
        for (AddFieldReq fieldReq : fieldReqs) {
            schema.addField(fieldReq);
        }
        // 添加 BM25 函数
        for (CreateCollectionReq.Function function : functions) {
            schema.addFunction(function);
        }
    }
}
