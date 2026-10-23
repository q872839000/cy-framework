package com.cy.redis.manager;

import com.cy.redis.config.properties.LocalCacheProperties;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

import java.util.concurrent.Callable;

/**
 * Caffeine 本地缓存装饰器。
 * <p>
 * 为单个 Spring Cache 实例补充一级本地缓存能力，
 * 形成“本地 Caffeine + 远端 Redis”的两级缓存模型。
 * </p>
 * <p>
 * 与旧实现相比，当前版本做了两点生产化增强：
 * <ul>
 *     <li>本地缓存按缓存实例隔离，避免全局共享带来的清理困难</li>
 *     <li>写入、清理、失效时同步维护本地缓存，保证单节点内一致性</li>
 * </ul>
 * </p>
 *
 * @author pcy
 * @see FlexSpringCacheManager
 */
public class CaffeineCacheDecorator implements Cache {

    private final Cache cache;
    private final com.github.benmanes.caffeine.cache.Cache<Object, ValueWrapper> caffeine;

    public CaffeineCacheDecorator(Cache cache, LocalCacheProperties localCacheProperties) {
        this.cache = cache;
        this.caffeine = Caffeine.newBuilder()
            .expireAfterWrite(localCacheProperties.getExpireAfterWrite())
            .initialCapacity(localCacheProperties.getInitialCapacity())
            .maximumSize(localCacheProperties.getMaximumSize())
            .build();
    }

    @Override
    public String getName() {
        return cache.getName();
    }

    @Override
    public Object getNativeCache() {
        return cache.getNativeCache();
    }

    @Override
    public ValueWrapper get(Object key) {
        return caffeine.get(key, k -> cache.get(key));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        if (wrapper == null) {
            return null;
        }
        Object value = wrapper.get();
        if (value != null && type != null && !type.isInstance(value)) {
            throw new IllegalStateException("Cached value is not of required type [" + type.getName() + "]: " + value);
        }
        return (T) value;
    }

    @Override
    public void put(Object key, Object value) {
        cache.put(key, value);
        caffeine.put(key, toValueWrapper(value));
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        ValueWrapper existing = cache.putIfAbsent(key, value);
        if (existing == null) {
            caffeine.put(key, toValueWrapper(value));
            return null;
        }
        caffeine.put(key, existing);
        return existing;
    }

    @Override
    public void evict(Object key) {
        evictIfPresent(key);
    }

    @Override
    public boolean evictIfPresent(Object key) {
        boolean evicted = cache.evictIfPresent(key);
        if (evicted) {
            caffeine.invalidate(key);
        }
        return evicted;
    }

    @Override
    public void clear() {
        cache.clear();
        caffeine.invalidateAll();
    }

    @Override
    public boolean invalidate() {
        boolean invalidated = cache.invalidate();
        caffeine.invalidateAll();
        return invalidated;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper wrapper = caffeine.get(key, k -> toValueWrapper(cache.get(key, valueLoader)));
        return wrapper != null ? (T) wrapper.get() : null;
    }

    private ValueWrapper toValueWrapper(Object value) {
        return new SimpleValueWrapper(value);
    }
}
