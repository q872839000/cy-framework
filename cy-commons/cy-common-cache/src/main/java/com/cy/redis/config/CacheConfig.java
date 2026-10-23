package com.cy.redis.config;

import com.cy.redis.aspect.CacheAspect;
import com.cy.redis.config.properties.LocalCacheProperties;
import com.cy.redis.manager.FlexSpringCacheManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * 缓存自动配置类。
 * <p>
 * 配置内容：
 * <ul>
 *     <li>FlexSpringCacheManager：负责 Spring Cache 与 Redis/Caffeine 两级缓存集成</li>
 *     <li>LocalCacheProperties：控制一级本地缓存开关、TTL 与容量</li>
 *     <li>CacheAspect：处理自定义缓存注解</li>
 * </ul>
 * </p>
 *
 * @author pcy
 * @see FlexSpringCacheManager
 * @see CacheAspect
 */
@AutoConfiguration
@EnableCaching
@EnableAspectJAutoProxy
@EnableConfigurationProperties(LocalCacheProperties.class)
public class CacheConfig {

    /**
     * 自定义缓存管理器，整合 Spring Cache。
     *
     * @param localCacheProperties 本地缓存配置
     * @return CacheManager
     */
    @Bean
    public CacheManager cacheManager(LocalCacheProperties localCacheProperties) {
        return new FlexSpringCacheManager(localCacheProperties);
    }

    /**
     * 缓存切面处理器。
     *
     * @return CacheAspect
     */
    @Bean
    public CacheAspect cacheAspect() {
        return new CacheAspect();
    }
}
