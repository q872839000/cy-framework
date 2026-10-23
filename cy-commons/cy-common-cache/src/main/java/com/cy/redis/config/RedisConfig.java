package com.cy.redis.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.cy.redis.config.properties.RedissonProperties;
import com.cy.redis.handler.KeyPrefixHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.CompositeCodec;
import org.redisson.codec.TypedJsonJacksonCodec;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.thread.Threading;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.task.VirtualThreadTaskExecutor;

import java.util.List;
import java.util.stream.Stream;

/**
 * Redisson 自动配置类
 * <p>
 * 功能说明：
 * <ul>
 *     <li>配置 Redisson 客户端的序列化方式（Key 使用 String，Value 使用 JSON）</li>
 *     <li>支持单机模式和集群模式</li>
 *     <li>支持服务私有 Key 前缀和跨服务共享 Key 前缀</li>
 *     <li>支持虚拟线程（Java 21+）</li>
 *     <li>启用 Lua 脚本缓存以提升性能</li>
 * </ul>
 *
 * @author pcy
 * @see RedissonProperties
 * @see KeyPrefixHandler
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(RedissonProperties.class)
public class RedisConfig {

    @Resource
    private RedissonProperties redissonProperties;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private ObjectProvider<RedisSharedKeyPrefixProvider> sharedKeyPrefixProviders;

    /**
     * 自定义 Redisson 配置
     * <p>
     * 配置内容：
     * <ul>
     *     <li>序列化：Key 使用 StringCodec，Value 使用 Jackson JSON</li>
     *     <li>线程池：根据配置设置工作线程数和 Netty 线程数</li>
     *     <li>虚拟线程：检测到虚拟线程环境时自动启用</li>
     *     <li>连接模式：根据配置自动选择单机或集群模式</li>
     * </ul>
     *
     * @param environment Spring 环境，用于检测虚拟线程
     * @return Redisson 配置定制器
     */
    @Bean
    public RedissonAutoConfigurationCustomizer redissonCustomizer(Environment environment) {
        return config -> {
            ObjectMapper om = objectMapper.copy();
            om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
            // 指定序列化输入的类型，类必须是非final修饰的。序列化时将对象全类名一起保存下来
            om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL);
            TypedJsonJacksonCodec jsonCodec = new TypedJsonJacksonCodec(Object.class, om);
            // 组合序列化 key 使用 String 内容使用通用 json 格式
            CompositeCodec codec = new CompositeCodec(StringCodec.INSTANCE, jsonCodec, jsonCodec);
            if (redissonProperties.getThreads() > 0) {
                config.setThreads(redissonProperties.getThreads());
            }
            if (redissonProperties.getNettyThreads() > 0) {
                config.setNettyThreads(redissonProperties.getNettyThreads());
            }
            // 缓存 Lua 脚本 减少网络传输(redisson 大部分的功能都是基于 Lua 脚本实现)
            config.setUseScriptCache(true)
                .setCodec(codec);
            if (Threading.VIRTUAL.isActive(environment)) {
                config.setNettyExecutor(new VirtualThreadTaskExecutor("redisson-"));
            }
            List<String> sharedKeyPrefixes = resolveSharedKeyPrefixes();
            RedissonProperties.SingleServerConfig singleServerConfig = redissonProperties.getSingleServerConfig();
            if (ObjectUtils.isNotEmpty(singleServerConfig)) {
                // 使用单机模式
                config.useSingleServer()
                    //设置redis key前缀
                    .setNameMapper(new KeyPrefixHandler(redissonProperties.getKeyPrefix(), sharedKeyPrefixes))
                    .setDatabase(singleServerConfig.getDatabase())
                    .setTimeout(singleServerConfig.getTimeout())
                    .setClientName(singleServerConfig.getClientName())
                    .setIdleConnectionTimeout(singleServerConfig.getIdleConnectionTimeout())
                    .setSubscriptionConnectionPoolSize(singleServerConfig.getSubscriptionConnectionPoolSize())
                    .setConnectionMinimumIdleSize(singleServerConfig.getConnectionMinimumIdleSize())
                    .setConnectionPoolSize(singleServerConfig.getConnectionPoolSize());
            }
            // 集群配置方式 参考下方注释
            RedissonProperties.ClusterServersConfig clusterServersConfig = redissonProperties.getClusterServersConfig();
            if (ObjectUtils.isNotEmpty(clusterServersConfig)) {
                config.useClusterServers()
                    //设置redis key前缀
                    .setNameMapper(new KeyPrefixHandler(redissonProperties.getKeyPrefix(), sharedKeyPrefixes))
                    .setTimeout(clusterServersConfig.getTimeout())
                    .setClientName(clusterServersConfig.getClientName())
                    .setIdleConnectionTimeout(clusterServersConfig.getIdleConnectionTimeout())
                    .setSubscriptionConnectionPoolSize(clusterServersConfig.getSubscriptionConnectionPoolSize())
                    .setMasterConnectionMinimumIdleSize(clusterServersConfig.getMasterConnectionMinimumIdleSize())
                    .setMasterConnectionPoolSize(clusterServersConfig.getMasterConnectionPoolSize())
                    .setSlaveConnectionMinimumIdleSize(clusterServersConfig.getSlaveConnectionMinimumIdleSize())
                    .setSlaveConnectionPoolSize(clusterServersConfig.getSlaveConnectionPoolSize())
                    .setReadMode(clusterServersConfig.getReadMode())
                    .setSubscriptionMode(clusterServersConfig.getSubscriptionMode());
            }
            log.info("初始化 redis 配置");
        };
    }

    private List<String> resolveSharedKeyPrefixes() {
        Stream<String> configuredPrefixes = redissonProperties.getSharedKeyPrefixes() == null
            ? Stream.empty()
            : redissonProperties.getSharedKeyPrefixes().stream();
        Stream<String> providerPrefixes = sharedKeyPrefixProviders.stream()
            .map(RedisSharedKeyPrefixProvider::sharedKeyPrefixes)
            .flatMap(prefixes -> prefixes == null ? Stream.empty() : prefixes.stream());
        return Stream.concat(configuredPrefixes, providerPrefixes)
            .map(StringUtils::trimToEmpty)
            .filter(StringUtils::isNotBlank)
            .distinct()
            .toList();
    }

}
