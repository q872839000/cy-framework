package com.cy.milvus.service;

import com.cy.core.exception.CYException;
import com.cy.milvus.core.MilvusClientManager;
import com.cy.milvus.vo.CollectionCreateRequest;
import io.milvus.v2.service.collection.request.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * @Auther: pcy
 * @Date: 2025/9/15 15:44
 * @Description: Collection 管理服务类（v2 API）
 * 提供集合创建、存在判断、删除、加载和释放操作
 */
public class CollectionService {

    private static final Log log = LogFactory.getLog(CollectionService.class);

    private final MilvusClientManager clientManager;

    public CollectionService(MilvusClientManager clientManager) {
        this.clientManager = clientManager;
    }

    /** 判断集合是否存在 */
    public boolean hasCollection(String name) {
        try {
            return clientManager.getClient().hasCollection(
                    HasCollectionReq.builder()
                            .collectionName(name)
                            .build()
            );
        } catch (Exception e) {
            log.error("检查集合是否存在失败: " + e.getMessage());
            throw new CYException(31010);
        }
    }

    /**
     * 创建集合（v2 API，支持 BM25 函数）
     * @param request 集合创建请求
     */
    public void createCollection(CollectionCreateRequest request) {
        try {
            CreateCollectionReq.CollectionSchema schema = request.getSchema();
            if (schema == null) {
                throw new CYException(31011);
            }

            // 构造 CreateCollectionReq
            CreateCollectionReq.CreateCollectionReqBuilder builder = CreateCollectionReq.builder()
                    .collectionName(request.getName())
                    .description(request.getDescription())
                    .collectionSchema(schema);

            // 设置分片数
            if (request.getShardsNum() != null && request.getShardsNum() > 0) {
                builder.numShards(request.getShardsNum());
            }

            // 设置索引参数（创建集合时同时创建索引）
            if (request.getIndexParams() != null && !request.getIndexParams().isEmpty()) {
                builder.indexParams(request.getIndexParams());
            }

            clientManager.getClient().createCollection(builder.build());
            log.info("集合创建成功: " + request.getName());
        } catch (CYException e) {
            throw e;
        } catch (Exception e) {
            log.error("创建集合失败: " + e.getMessage(), e);
            throw new CYException(31012);
        }
    }

    /** 删除集合 */
    public void dropCollection(String name) {
        try {
            clientManager.getClient().dropCollection(
                    DropCollectionReq.builder()
                            .collectionName(name)
                            .build()
            );
            log.info("集合删除成功: " + name);
        } catch (Exception e) {
            log.error("删除集合失败: " + e.getMessage());
            throw new CYException(31013);
        }
    }

    /** 加载集合到内存 */
    public void loadCollection(String name) {
        try {
            clientManager.getClient().loadCollection(
                    LoadCollectionReq.builder()
                            .collectionName(name)
                            .build()
            );
            log.info("集合加载成功: " + name);
        } catch (Exception e) {
            log.error("加载集合失败: " + e.getMessage());
            throw new CYException(31014);
        }
    }

    /** 释放集合内存 */
    public void releaseCollection(String name) {
        try {
            clientManager.getClient().releaseCollection(
                    ReleaseCollectionReq.builder()
                            .collectionName(name)
                            .build()
            );
            log.info("集合释放成功: " + name);
        } catch (Exception e) {
            log.error("释放集合失败: " + e.getMessage());
            throw new CYException(31015);
        }
    }
}
