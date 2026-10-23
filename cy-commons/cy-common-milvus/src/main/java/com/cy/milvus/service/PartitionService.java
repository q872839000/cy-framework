package com.cy.milvus.service;

import com.cy.core.exception.CYException;
import com.cy.milvus.core.MilvusClientManager;
import io.milvus.v2.service.partition.request.CreatePartitionReq;
import io.milvus.v2.service.partition.request.DropPartitionReq;
import io.milvus.v2.service.partition.request.HasPartitionReq;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * @Auther: pcy
 * @Date: 2025/9/15 15:44
 * @Description: 分区管理服务类（v2 API）
 * 提供创建、判断存在、删除分区功能
 */
public class PartitionService {

    private static final Log log = LogFactory.getLog(PartitionService.class);

    private final MilvusClientManager clientManager;

    public PartitionService(MilvusClientManager clientManager) {
        this.clientManager = clientManager;
    }

    /** 判断分区是否存在 */
    public boolean hasPartition(String collectionName, String partitionName) {
        try {
            return clientManager.getClient().hasPartition(
                    HasPartitionReq.builder()
                            .collectionName(collectionName)
                            .partitionName(partitionName)
                            .build()
            );
        } catch (Exception e) {
            log.error("检查分区是否存在失败: " + e.getMessage());
            throw new CYException(31030);
        }
    }

    /** 创建分区 */
    public void createPartition(String collectionName, String partitionName) {
        try {
            clientManager.getClient().createPartition(
                    CreatePartitionReq.builder()
                            .collectionName(collectionName)
                            .partitionName(partitionName)
                            .build()
            );
            log.info("分区创建成功: " + collectionName + "/" + partitionName);
        } catch (Exception e) {
            log.error("创建分区失败: " + e.getMessage());
            throw new CYException(31031);
        }
    }

    /** 删除分区 */
    public void dropPartition(String collectionName, String partitionName) {
        try {
            clientManager.getClient().dropPartition(
                    DropPartitionReq.builder()
                            .collectionName(collectionName)
                            .partitionName(partitionName)
                            .build()
            );
            log.info("分区删除成功: " + collectionName + "/" + partitionName);
        } catch (Exception e) {
            log.error("删除分区失败: " + e.getMessage());
            throw new CYException(31032);
        }
    }
}