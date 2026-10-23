package com.cy.milvus.service;

import com.cy.core.exception.CYException;
import com.cy.milvus.core.MilvusClientManager;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.index.request.DropIndexReq;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Collections;

/**
 * @Auther: pcy
 * @Date: 2025/9/15 15:44
 * @Description: 索引管理服务类（v2 API）
 * 提供创建索引、删除索引功能
 * 注意：v2 API 推荐在创建集合时一并创建索引，此类用于单独管理索引
 */
public class IndexService {

    private static final Log log = LogFactory.getLog(IndexService.class);

    private final MilvusClientManager clientManager;

    public IndexService(MilvusClientManager clientManager) {
        this.clientManager = clientManager;
    }

    /**
     * 创建索引（v2 API）
     * @param collectionName 集合名称
     * @param indexParam 索引参数
     */
    public void createIndex(String collectionName, IndexParam indexParam) {
        try {
            CreateIndexReq req = CreateIndexReq.builder()
                    .collectionName(collectionName)
                    .indexParams(Collections.singletonList(indexParam))
                    .build();

            clientManager.getClient().createIndex(req);
            log.info("索引创建成功: collection=" + collectionName + ", field=" + indexParam.getFieldName());
        } catch (Exception e) {
            log.error("创建索引失败: " + e.getMessage(), e);
            throw new CYException(31020);
        }
    }

    /**
     * 删除索引
     * @param collectionName 集合名称
     * @param fieldName 字段名称
     */
    public void dropIndex(String collectionName, String fieldName) {
        if (collectionName == null || collectionName.isBlank()) {
            throw new IllegalArgumentException("collectionName 不能为空");
        }
        try {
            DropIndexReq req = DropIndexReq.builder()
                    .collectionName(collectionName)
                    .fieldName(fieldName)
                    .build();

            clientManager.getClient().dropIndex(req);
            log.info("索引删除成功: collection=" + collectionName + ", field=" + fieldName);
        } catch (Exception e) {
            log.error("删除索引失败: " + e.getMessage(), e);
            throw new CYException(31022);
        }
    }
}