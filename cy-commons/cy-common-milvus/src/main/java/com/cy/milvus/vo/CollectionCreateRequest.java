package com.cy.milvus.vo;

import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2025/9/17 11:54
 * @Description: 创建集合请求（v2 API）
 * 支持 BM25 函数定义
 */
@Data
@Builder
public class CollectionCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 集合名称 */
    private String name;

    /** 集合描述 */
    private String description;

    /** 分片数 */
    private Integer shardsNum;

    /** v2 API Schema（包含字段和函数定义） */
    private CreateCollectionReq.CollectionSchema schema;

    /** 索引参数列表 */
    private List<IndexParam> indexParams;
}
