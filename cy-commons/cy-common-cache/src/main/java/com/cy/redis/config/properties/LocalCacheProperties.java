package com.cy.redis.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 本地缓存配置属性。
 * <p>
 * 用于控制 Spring Cache 一级 Caffeine 缓存的开关与容量参数。
 * 当前仅作用于 FlexSpringCacheManager 管理的缓存路径，
 * 不影响 RedisUtils 直连 Redis 的调用方式。
 * </p>
 *
 * @author pcy
 */
@Data
@ConfigurationProperties(prefix = "cy.cache.local")
public class LocalCacheProperties {

    /**
     * 是否启用本地一级缓存。
     */
    private boolean enabled = true;

    /**
     * 写入后过期时间。
     */
    private Duration expireAfterWrite = Duration.ofSeconds(30);

    /**
     * 初始容量。
     */
    private int initialCapacity = 100;

    /**
     * 最大条目数。
     */
    private long maximumSize = 1000;
}
