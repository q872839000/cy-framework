/**
 * Copyright (c) 2013-2021 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cy.redis.manager;

import com.cy.redis.config.properties.LocalCacheProperties;
import com.cy.redis.utils.RedisUtils;
import org.redisson.api.RMap;
import org.redisson.api.RMapCache;
import org.redisson.spring.cache.CacheConfig;
import org.redisson.spring.cache.RedissonCache;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 灵活的 Spring Cache 管理器。
 * <p>
 * 基于 RedissonSpringCacheManager 改造，支持通过 cacheName 动态声明 Redis TTL、
 * 最大空闲时间和最大条目数，并按配置决定是否为每个缓存实例附加本地 Caffeine 一级缓存。
 * </p>
 * <p>
 * cacheName 格式：{@code name#ttl#maxIdleTime#maxSize}
 * </p>
 *
 * @author pcy
 * @see CaffeineCacheDecorator
 */
@SuppressWarnings("unchecked")
public class FlexSpringCacheManager implements CacheManager {

    private final LocalCacheProperties localCacheProperties;

    private boolean dynamic = true;

    private boolean allowNullValues = true;

    private boolean transactionAware = true;

    private Map<String, CacheConfig> configMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Cache> instanceMap = new ConcurrentHashMap<>();

    public FlexSpringCacheManager(LocalCacheProperties localCacheProperties) {
        this.localCacheProperties = localCacheProperties;
    }

    /**
     * 是否允许缓存 null 值。
     *
     * @param allowNullValues true=允许缓存 null
     */
    public void setAllowNullValues(boolean allowNullValues) {
        this.allowNullValues = allowNullValues;
    }

    /**
     * 是否开启事务感知。
     *
     * @param transactionAware true=事务提交后再生效
     */
    public void setTransactionAware(boolean transactionAware) {
        this.transactionAware = transactionAware;
    }

    /**
     * 固定缓存名称集合；传 null 则恢复动态模式。
     *
     * @param names 缓存名集合
     */
    public void setCacheNames(Collection<String> names) {
        if (names != null) {
            for (String name : names) {
                getCache(name);
            }
            dynamic = false;
        } else {
            dynamic = true;
        }
    }

    /**
     * 设置缓存配置映射。
     *
     * @param config 配置映射
     */
    @SuppressWarnings("unchecked")
    public void setConfig(Map<String, ? extends CacheConfig> config) {
        this.configMap = (Map<String, CacheConfig>) config;
    }

    protected CacheConfig createDefaultConfig() {
        return new CacheConfig();
    }

    @Override
    public Cache getCache(String name) {
        String[] array = StringUtils.delimitedListToStringArray(name, "#");
        name = array[0];

        Cache cache = instanceMap.get(name);
        if (cache != null) {
            return cache;
        }
        if (!dynamic) {
            return null;
        }

        CacheConfig config = configMap.computeIfAbsent(name, key -> createDefaultConfig());
        applyDynamicConfig(config, array);

        if (config.getMaxIdleTime() == 0 && config.getTTL() == 0 && config.getMaxSize() == 0) {
            return createMap(name);
        }
        return createMapCache(name, config);
    }

    @Override
    public Collection<String> getCacheNames() {
        return Collections.unmodifiableSet(configMap.keySet());
    }

    private void applyDynamicConfig(CacheConfig config, String[] array) {
        if (array.length > 1) {
            config.setTTL(DurationStyle.detectAndParse(array[1]).toMillis());
        }
        if (array.length > 2) {
            config.setMaxIdleTime(DurationStyle.detectAndParse(array[2]).toMillis());
        }
        if (array.length > 3) {
            config.setMaxSize(Integer.parseInt(array[3]));
        }
    }

    private Cache createMap(String name) {
        RMap<Object, Object> map = RedisUtils.getClient().getMap(name);
        Cache cache = decorate(new RedissonCache(map, allowNullValues));
        if (transactionAware) {
            cache = new TransactionAwareCacheDecorator(cache);
        }
        Cache oldCache = instanceMap.putIfAbsent(name, cache);
        return oldCache != null ? oldCache : cache;
    }

    private Cache createMapCache(String name, CacheConfig config) {
        RMapCache<Object, Object> map = RedisUtils.getClient().getMapCache(name);
        Cache cache = decorate(new RedissonCache(map, config, allowNullValues));
        if (transactionAware) {
            cache = new TransactionAwareCacheDecorator(cache);
        }
        Cache oldCache = instanceMap.putIfAbsent(name, cache);
        if (oldCache != null) {
            return oldCache;
        }
        map.setMaxSize(config.getMaxSize());
        return cache;
    }

    private Cache decorate(Cache cache) {
        if (!localCacheProperties.isEnabled()) {
            return cache;
        }
        return new CaffeineCacheDecorator(cache, localCacheProperties);
    }
}
