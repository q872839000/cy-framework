package com.cy.redis.config;

import java.util.Collection;

/**
 * Redis 共享 key 前缀贡献者。
 * <p>
 * 业务组件可通过注册该 Bean 声明需要跨服务共享的逻辑 key 前缀，cache 组件会在 Redisson 初始化时统一收集。
 * </p>
 */
public interface RedisSharedKeyPrefixProvider {

    /**
     * 返回需要进入共享 Redis 命名空间的逻辑 key 前缀。
     */
    Collection<String> sharedKeyPrefixes();
}
