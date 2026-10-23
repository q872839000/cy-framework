package com.cy.redis.utils;

import com.cy.core.utils.SpringUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.redisson.api.RMap;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Set;

/**
 * Spring Cache 操作工具类
 * <p>
 * 基于 {@link CacheManager} 封装的缓存操作工具，用于操作 Spring Cache 管理的缓存。
 * <p>
 * 与 {@link RedisUtils} 的区别：
 * <ul>
 *     <li>CacheUtils：操作 Spring Cache 管理的缓存（支持二级缓存架构）</li>
 *     <li>RedisUtils：直接操作 Redis（绕过 Spring Cache 抽象层）</li>
 * </ul>
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 获取缓存
 * User user = CacheUtils.get("users", "user:1");
 *
 * // 设置缓存
 * CacheUtils.put("users", "user:1", user);
 *
 * // 删除缓存
 * CacheUtils.evict("users", "user:1");
 * }</pre>
 *
 * @author pcy
 * @see CacheManager
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CacheUtils {

    private static volatile CacheManager CACHE_MANAGER;

    /**
     * 获取CacheManager实例（懒加载）
     */
    private static CacheManager getCacheManager() {
        if (CACHE_MANAGER == null) {
            synchronized (CacheUtils.class) {
                if (CACHE_MANAGER == null) {
                    CACHE_MANAGER = SpringUtils.getBean(CacheManager.class);
                }
            }
        }
        return CACHE_MANAGER;
    }

    /**
     * 获取缓存组内所有的KEY
     *
     * @param cacheNames 缓存组名称
     */
    @SuppressWarnings("unchecked")
    public static Set<Object> keys(String cacheNames) {
        RMap<Object, Object> rmap = (RMap<Object, Object>) getCacheManager().getCache(cacheNames).getNativeCache();
        return rmap.keySet();
    }

    /**
     * 获取缓存值
     *
     * @param cacheNames 缓存组名称
     * @param key        缓存key
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(String cacheNames, Object key) {
        Cache.ValueWrapper wrapper = getCacheManager().getCache(cacheNames).get(key);
        return wrapper != null ? (T) wrapper.get() : null;
    }

    /**
     * 保存缓存值
     *
     * @param cacheNames 缓存组名称
     * @param key        缓存key
     * @param value      缓存值
     */
    public static void put(String cacheNames, Object key, Object value) {
        getCacheManager().getCache(cacheNames).put(key, value);
    }

    /**
     * 删除缓存值
     *
     * @param cacheNames 缓存组名称
     * @param key        缓存key
     */
    public static void evict(String cacheNames, Object key) {
        getCacheManager().getCache(cacheNames).evict(key);
    }

    /**
     * 清空缓存值
     *
     * @param cacheNames 缓存组名称
     */
    public static void clear(String cacheNames) {
        getCacheManager().getCache(cacheNames).clear();
    }

}
