package com.cy.redis.utils;

import com.cy.core.utils.SpringUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.redisson.api.*;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Redis 操作工具类
 * <p>
 * 基于 Redisson 封装的 Redis 操作工具，提供丰富的数据结构操作：
 * <ul>
 *     <li>基础操作：String 类型的存取、过期时间设置</li>
 *     <li>List 操作：列表的增删查</li>
 *     <li>Set 操作：集合的增删查</li>
 *     <li>Map/Hash 操作：哈希表的增删查</li>
 *     <li>原子操作：计数器的递增递减</li>
 *     <li>限流：基于令牌桶的分布式限流</li>
 * </ul>
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 缓存对象
 * RedisUtils.setCacheObject("user:1", user, Duration.ofMinutes(30));
 * User user = RedisUtils.getCacheObject("user:1");
 *
 * // 限流
 * long permits = RedisUtils.rateLimiter("api:limit", RateType.OVERALL, 100, 60);
 * }</pre>
 *
 * @author pcy
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RedisUtils {

    private static volatile RedissonClient CLIENT;

    /**
     * 获取RedissonClient实例（懒加载）
     */
    private static RedissonClient getRedissonClient() {
        if (CLIENT == null) {
            synchronized (RedisUtils.class) {
                if (CLIENT == null) {
                    CLIENT = SpringUtils.getBean(RedissonClient.class);
                }
            }
        }
        return CLIENT;
    }


    /**
     * 限流
     *
     * @param key          限流key
     * @param rateType     限流类型
     * @param rate         速率
     * @param rateInterval 速率间隔
     * @return -1 表示失败
     */
    public static long rateLimiter(String key, RateType rateType, int rate, int rateInterval) {
        RRateLimiter rateLimiter = getRedissonClient().getRateLimiter(key);
        rateLimiter.trySetRate(rateType, rate, rateInterval, RateIntervalUnit.SECONDS);
        if (rateLimiter.tryAcquire()) {
            return rateLimiter.availablePermits();
        } else {
            return -1L;
        }
    }

    /**
     * 获取客户端实例
     */
    public static RedissonClient getClient() {
        return getRedissonClient();
    }

    /**
     * 缓存基本的对象，Integer、String、实体类等
     *
     * @param key   缓存的键值
     * @param value 缓存的值
     */
    public static <T> void setCacheObject(final String key, final T value) {
        setCacheObject(key, value, false);
    }

    /**
     * 缓存基本的对象，保留当前对象 TTL 有效期
     *
     * @param key       缓存的键值
     * @param value     缓存的值
     * @param isSaveTtl 是否保留TTL有效期(例如: set之前ttl剩余90 set之后还是为90)
     * @since Redis 6.X 以上使用 setAndKeepTTL 兼容 5.X 方案
     */
    public static <T> void setCacheObject(final String key, final T value, final boolean isSaveTtl) {
        RBucket<T> bucket = getRedissonClient().getBucket(key);
        if (isSaveTtl) {
            try {
                bucket.setAndKeepTTL(value);
            } catch (Exception e) {
                long timeToLive = bucket.remainTimeToLive();
                setCacheObject(key, value, Duration.ofMillis(timeToLive));
            }
        } else {
            bucket.set(value);
        }
    }

    /**
     * 缓存基本的对象，Integer、String、实体类等
     *
     * @param key      缓存的键值
     * @param value    缓存的值
     * @param duration 时间
     */
    public static <T> void setCacheObject(final String key, final T value, final Duration duration) {
        RBucket<T> bucket = getRedissonClient().getBucket(key);
        bucket.set(value, duration);
    }

    /**
     * 如果不存在则设置 并返回 true 如果存在则返回 false
     *
     * @param key   缓存的键值
     * @param value 缓存的值
     * @return set成功或失败
     */
    public static <T> boolean setObjectIfAbsent(final String key, final T value, final Duration duration) {
        RBucket<T> bucket = getRedissonClient().getBucket(key);
        return bucket.setIfAbsent(value, duration);
    }

    /**
     * 如果存在则设置 并返回 true 如果存在则返回 false
     *
     * @param key   缓存的键值
     * @param value 缓存的值
     * @return set成功或失败
     */
    public static <T> boolean setObjectIfExists(final String key, final T value, final Duration duration) {
        RBucket<T> bucket = getRedissonClient().getBucket(key);
        return bucket.setIfExists(value, duration);
    }

    /**
     * 注册对象监听器
     * <p>
     * key 监听器需开启 `notify-keyspace-events` 等 redis 相关配置
     *
     * @param key      缓存的键值
     * @param listener 监听器配置
     */
    public static <T> void addObjectListener(final String key, final ObjectListener listener) {
        RBucket<T> result = getRedissonClient().getBucket(key);
        result.addListener(listener);
    }

    /**
     * 设置有效时间
     *
     * @param key     Redis键
     * @param timeout 超时时间
     * @return true=设置成功；false=设置失败
     */
    public static boolean expire(final String key, final long timeout) {
        return expire(key, Duration.ofSeconds(timeout));
    }

    /**
     * 设置有效时间
     *
     * @param key      Redis键
     * @param duration 超时时间
     * @return true=设置成功；false=设置失败
     */
    public static boolean expire(final String key, final Duration duration) {
        RBucket<?> rBucket = getRedissonClient().getBucket(key);
        return rBucket.expire(duration);
    }

    /**
     * 获得缓存的基本对象。
     *
     * @param key 缓存键值
     * @return 缓存键值对应的数据
     */
    public static <T> T getCacheObject(final String key) {
        RBucket<T> rBucket = getRedissonClient().getBucket(key);
        return rBucket.get();
    }

    /**
     * 获得key剩余存活时间
     *
     * @param key 缓存键值
     * @return 剩余存活时间
     */
    public static <T> long getTimeToLive(final String key) {
        RBucket<T> rBucket = getRedissonClient().getBucket(key);
        return rBucket.remainTimeToLive();
    }

    /**
     * 删除单个对象
     *
     * @param key 缓存的键值
     */
    public static boolean deleteObject(final String key) {
        return getRedissonClient().getBucket(key).delete();
    }

    /**
     * 删除集合对象
     *
     * @param collection 多个对象
     */
    public static void deleteObject(final Collection<?> collection) {
        RBatch batch = getRedissonClient().createBatch();
        collection.forEach(t -> batch.getBucket(t.toString()).deleteAsync());
        batch.execute();
    }

    /**
     * 检查缓存对象是否存在
     *
     * @param key 缓存的键值
     */
    public static boolean isExistsObject(final String key) {
        return getRedissonClient().getBucket(key).isExists();
    }

    /**
     * 缓存List数据
     *
     * @param key      缓存的键值
     * @param dataList 待缓存的List数据
     * @return 缓存的对象
     */
    public static <T> boolean setCacheList(final String key, final List<T> dataList) {
        RList<T> rList = getRedissonClient().getList(key);
        return rList.addAll(dataList);
    }

    /**
     * 追加缓存List数据
     *
     * @param key  缓存的键值
     * @param data 待缓存的数据
     * @return 缓存的对象
     */
    public static <T> boolean addCacheList(final String key, final T data) {
        RList<T> rList = getRedissonClient().getList(key);
        return rList.add(data);
    }

    /**
     * 注册List监听器
     * <p>
     * key 监听器需开启 `notify-keyspace-events` 等 redis 相关配置
     *
     * @param key      缓存的键值
     * @param listener 监听器配置
     */
    public static <T> void addListListener(final String key, final ObjectListener listener) {
        RList<T> rList = getRedissonClient().getList(key);
        rList.addListener(listener);
    }

    /**
     * 获得缓存的list对象
     *
     * @param key 缓存的键值
     * @return 缓存键值对应的数据
     */
    public static <T> List<T> getCacheList(final String key) {
        RList<T> rList = getRedissonClient().getList(key);
        return rList.readAll();
    }

    /**
     * 获得缓存的list对象(范围)
     *
     * @param key  缓存的键值
     * @param form 起始下标
     * @param to   截止下标
     * @return 缓存键值对应的数据
     */
    public static <T> List<T> getCacheListRange(final String key, int form, int to) {
        RList<T> rList = getRedissonClient().getList(key);
        return rList.range(form, to);
    }

    /**
     * 缓存Set
     *
     * @param key     缓存键值
     * @param dataSet 缓存的数据
     * @return 缓存数据的对象
     */
    public static <T> boolean setCacheSet(final String key, final Set<T> dataSet) {
        RSet<T> rSet = getRedissonClient().getSet(key);
        return rSet.addAll(dataSet);
    }

    /**
     * 追加缓存Set数据
     *
     * @param key  缓存的键值
     * @param data 待缓存的数据
     * @return 缓存的对象
     */
    public static <T> boolean addCacheSet(final String key, final T data) {
        RSet<T> rSet = getRedissonClient().getSet(key);
        return rSet.add(data);
    }

    /**
     * 删除 Set 中的指定元素
     *
     * @param key  缓存的键值
     * @param data 待删除的数据
     * @return true=删除成功，false=元素不存在
     */
    public static boolean removeCacheSetValue(final String key, final Object data) {
        RSet<Object> rSet = getRedissonClient().getSet(key);
        return rSet.remove(data);
    }

    /**
     * 注册Set监听器
     * <p>
     * key 监听器需开启 `notify-keyspace-events` 等 redis 相关配置
     *
     * @param key      缓存的键值
     * @param listener 监听器配置
     */
    public static <T> void addSetListener(final String key, final ObjectListener listener) {
        RSet<T> rSet = getRedissonClient().getSet(key);
        rSet.addListener(listener);
    }

    /**
     * 获得缓存的set
     *
     * @param key 缓存的key
     * @return set对象
     */
    public static <T> Set<T> getCacheSet(final String key) {
        RSet<T> rSet = getRedissonClient().getSet(key);
        return rSet.readAll();
    }

    /**
     * 缓存Map
     *
     * @param key     缓存的键值
     * @param dataMap 缓存的数据
     */
    public static <T> void setCacheMap(final String key, final Map<String, T> dataMap) {
        if (dataMap != null && !dataMap.isEmpty()) {
            RMap<String, T> rMap = getRedissonClient().getMap(key);
            rMap.putAll(dataMap);
        }
    }

    /**
     * 注册Map监听器
     * <p>
     * key 监听器需开启 `notify-keyspace-events` 等 redis 相关配置
     *
     * @param key      缓存的键值
     * @param listener 监听器配置
     */
    public static <T> void addMapListener(final String key, final ObjectListener listener) {
        RMap<String, T> rMap = getRedissonClient().getMap(key);
        rMap.addListener(listener);
    }

    /**
     * 获得缓存的Map
     *
     * @param key 缓存的键值
     * @return map对象
     */
    public static <T> Map<String, T> getCacheMap(final String key) {
        RMap<String, T> rMap = getRedissonClient().getMap(key);
        if (rMap != null && rMap.isExists()) {
            Set<String> keySet = rMap.keySet();
            return rMap.getAll(keySet);
        }
        return Map.of();
    }

    /**
     * 获得缓存Map的key列表
     *
     * @param key 缓存的键值
     * @return key列表
     */
    public static <T> Set<String> getCacheMapKeySet(final String key) {
        RMap<String, T> rMap = getRedissonClient().getMap(key);
        if (rMap != null && rMap.isExists()) {
            Set<String> keySet = rMap.keySet();
            return keySet != null ? keySet : Set.of();
        }
        return Set.of();
    }

    /**
     * 往Hash中存入数据
     *
     * @param key   Redis键
     * @param hKey  Hash键
     * @param value 值
     */
    public static <T> void setCacheMapValue(final String key, final String hKey, final T value) {
        if (hKey != null && value != null) {
            RMap<String, T> rMap = getRedissonClient().getMap(key);
            if (rMap != null) {
                rMap.put(hKey, value);
            }
        }
    }

    /**
     * 获取Hash中的数据
     *
     * @param key  Redis键
     * @param hKey Hash键
     * @return Hash中的对象
     */
    public static <T> T getCacheMapValue(final String key, final String hKey) {
        if (hKey != null) {
            RMap<String, T> rMap = getRedissonClient().getMap(key);
            if (rMap != null && rMap.isExists()) {
                return rMap.get(hKey);
            }
        }
        return null;
    }

    /**
     * 删除Hash中的数据
     *
     * @param key  Redis键
     * @param hKey Hash键
     * @return Hash中的对象
     */
    public static <T> T delCacheMapValue(final String key, final String hKey) {
        RMap<String, T> rMap = getRedissonClient().getMap(key);
        return rMap.remove(hKey);
    }

    /**
     * 删除Hash中的数据
     *
     * @param key   Redis键
     * @param hKeys Hash键
     */
    public static <T> void delMultiCacheMapValue(final String key, final Set<String> hKeys) {
        RBatch batch = getRedissonClient().createBatch();
        RMapAsync<String, T> rMap = batch.getMap(key);
        for (String hKey : hKeys) {
            rMap.removeAsync(hKey);
        }
        batch.execute();
    }

    /**
     * 获取多个Hash中的数据
     *
     * @param key   Redis键
     * @param hKeys Hash键集合
     * @return Hash对象集合
     */
    public static <K, V> Map<K, V> getMultiCacheMapValue(final String key, final Set<K> hKeys) {
        RMap<K, V> rMap = getRedissonClient().getMap(key);
        return rMap.getAll(hKeys);
    }

    /**
     * 设置原子值
     *
     * @param key   Redis键
     * @param value 值
     */
    public static void setAtomicValue(String key, long value) {
        RAtomicLong atomic = getRedissonClient().getAtomicLong(key);
        atomic.set(value);
    }

    /**
     * 获取原子值
     *
     * @param key Redis键
     * @return 当前值
     */
    public static long getAtomicValue(String key) {
        RAtomicLong atomic = getRedissonClient().getAtomicLong(key);
        return atomic.get();
    }

    /**
     * 递增原子值
     *
     * @param key Redis键
     * @return 当前值
     */
    public static long incrAtomicValue(String key) {
        RAtomicLong atomic = getRedissonClient().getAtomicLong(key);
        return atomic.incrementAndGet();
    }

    /**
     * 递增原子值，并刷新过期时间
     *
     * @param key      Redis键
     * @param duration 过期时间
     * @return 当前值
     */
    public static long incrAtomicValue(String key, Duration duration) {
        RAtomicLong atomic = getRedissonClient().getAtomicLong(key);
        long value = atomic.incrementAndGet();
        if (duration != null && !duration.isZero() && !duration.isNegative()) {
            atomic.expire(duration);
        }
        return value;
    }

    /**
     * 递减原子值
     *
     * @param key Redis键
     * @return 当前值
     */
    public static long decrAtomicValue(String key) {
        RAtomicLong atomic = getRedissonClient().getAtomicLong(key);
        return atomic.decrementAndGet();
    }

    /**
     * 获得缓存的基本对象列表
     *
     * @param pattern 字符串前缀
     * @return 对象列表
     */
    public static Collection<String> keys(final String pattern) {
        Stream<String> stream = getRedissonClient().getKeys().getKeysStreamByPattern(pattern);
        return stream.collect(Collectors.toList());
    }

    /**
     * 删除缓存的基本对象列表
     *
     * @param pattern 字符串前缀
     */
    public static void deleteKeys(final String pattern) {
        getRedissonClient().getKeys().deleteByPattern(pattern);
    }

    /**
     * 检查redis中是否存在key
     *
     * @param key 键
     */
    public static Boolean hasKey(String key) {
        RKeys rKeys = getRedissonClient().getKeys();
        return rKeys.countExists(key) > 0;
    }
}
