package com.cy.milvus.core;

import com.cy.core.exception.CYException;
import com.cy.milvus.config.MilvusProperties;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * @Auther: pcy
 * @Date: 2025/9/15 15:44
 * @Description: Milvus客户端管理类（v2 API）
 * 单例维护 MilvusClientV2，提供连接管理、健康检查、关闭等操作
 */
public class MilvusClientManager {

    private static final Log log = LogFactory.getLog(MilvusClientManager.class);
    /** Milvus SDK v2 客户端实例 */
    private MilvusClientV2 client = null;

    private MilvusProperties props;

    public MilvusClientManager(MilvusProperties props) {
        try {
            this.props = props;
            if (props.getIsConnect() && StringUtils.isNoneBlank(props.getHost())) {
                // 构建 URI: http://host:port
                String uri = String.format("http://%s:%d", props.getHost(), props.getPort());
                
                ConnectConfig.ConnectConfigBuilder configBuilder = ConnectConfig.builder()
                        .uri(uri)
                        .connectTimeoutMs(props.getTimeout().toMillis());
                
                // 如果有 token 则添加
                if (StringUtils.isNotBlank(props.getToken())) {
                    configBuilder.token(props.getToken());
                }
                
                this.client = new MilvusClientV2(configBuilder.build());
                log.info("连接 Milvus服务成功 (v2 API)");
            } else {
                props.setIsConnect(Boolean.FALSE);
                log.info("未配置 Milvus连接");
            }
        } catch (Exception e) {
            props.setIsConnect(Boolean.FALSE);
            log.error("连接 Milvus服务失败: " + e.getMessage());
        }
    }

    /** 获取 v2 客户端实例 */
    public MilvusClientV2 getClient() {
        if (!props.getIsConnect()) {
            throw new CYException(31041);
        }
        return client;
    }

    /**
     * 创建 Schema（用于 v2 API 创建集合）
     * @return CollectionSchema
     */
    public io.milvus.v2.service.collection.request.CreateCollectionReq.CollectionSchema createSchema() {
        return client.createSchema();
    }

    /**
     * 健康检查
     * @return true 表示服务健康
     */
    public boolean healthCheck() {
        try {
            // v2 API 暂无直接健康检查，通过 listCollections 间接检测
            client.listCollections();
            return true;
        } catch (Exception e) {
            log.warn("Milvus health check failed", e);
            return false;
        }
    }

    /**
     * 关闭客户端连接
     */
    public void close() {
        if (client != null) {
            try {
                client.close();
                log.info("Milvus 客户端已关闭");
            } catch (Exception e) {
                log.warn("关闭 Milvus 客户端失败", e);
            }
        }
    }
}
