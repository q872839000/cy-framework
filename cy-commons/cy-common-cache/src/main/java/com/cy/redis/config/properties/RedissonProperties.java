package com.cy.redis.config.properties;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.redisson.config.ReadMode;
import org.redisson.config.SubscriptionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Redisson 配置属性
 * <p>
 * 配置前缀：{@code cy.cache.redis}
 * <p>
 * 支持两种部署模式：
 * <ul>
 *     <li>单机模式：配置 {@link #singleServerConfig}</li>
 *     <li>集群模式：配置 {@link #clusterServersConfig}</li>
 * </ul>
 * <p>
 * 注意：单机模式和集群模式只能选择其一
 *
 * @author pcy
 */
@Data
@ConfigurationProperties(prefix = "cy.cache.redis")
public class RedissonProperties {

    /**
     * Redis 服务私有 key 前缀。
     * <p>
     * 默认 Redis 操作会通过 Redisson {@code NameMapper} 自动叠加该前缀，用于多应用共享同一 Redis 时隔离各自私有缓存。
     * 跨服务共享数据应通过 {@link #sharedKeyPrefixes} 或 {@code RedisSharedKeyPrefixProvider} 声明共享前缀。
     * </p>
     */
    private String keyPrefix;

    /**
     * 跨服务共享 key 前缀。
     * <p>
     * 命中这些逻辑前缀的 key 会保持原始 key，不再叠加当前服务的 {@link #keyPrefix}。
     * 通用组件不应写死业务前缀，业务组件可通过 {@code RedisSharedKeyPrefixProvider} 自动贡献共享前缀。
     * </p>
     */
    private List<String> sharedKeyPrefixes = new ArrayList<>();

    /**
     * 线程池数量,默认值 = 当前处理核数量 * 2
     */
    private int threads;

    /**
     * Netty线程池数量,默认值 = 当前处理核数量 * 2
     */
    private int nettyThreads;

    /**
     * 单机服务配置
     */
    private SingleServerConfig singleServerConfig;

    /**
     * 集群服务配置
     */
    private ClusterServersConfig clusterServersConfig;

    @Data
    @NoArgsConstructor
    public static class SingleServerConfig {

        /**
         * 客户端名称
         */
        private String clientName;

        /**
         * 数据库编号
         */
        private int database;

        /**
         * 最小空闲连接数
         */
        private int connectionMinimumIdleSize;

        /**
         * 连接池大小
         */
        private int connectionPoolSize;

        /**
         * 连接空闲超时，单位：毫秒
         */
        private int idleConnectionTimeout;

        /**
         * 命令等待超时，单位：毫秒
         */
        private int timeout;

        /**
         * 发布和订阅连接池大小
         */
        private int subscriptionConnectionPoolSize;

    }

    @Data
    @NoArgsConstructor
    public static class ClusterServersConfig {

        /**
         * 客户端名称
         */
        private String clientName;

        /**
         * master最小空闲连接数
         */
        private int masterConnectionMinimumIdleSize;

        /**
         * master连接池大小
         */
        private int masterConnectionPoolSize;

        /**
         * slave最小空闲连接数
         */
        private int slaveConnectionMinimumIdleSize;

        /**
         * slave连接池大小
         */
        private int slaveConnectionPoolSize;

        /**
         * 连接空闲超时，单位：毫秒
         */
        private int idleConnectionTimeout;

        /**
         * 命令等待超时，单位：毫秒
         */
        private int timeout;

        /**
         * 发布和订阅连接池大小
         */
        private int subscriptionConnectionPoolSize;

        /**
         * 读取模式
         */
        private ReadMode readMode;

        /**
         * 订阅模式
         */
        private SubscriptionMode subscriptionMode;

    }

}
