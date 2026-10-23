// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.context;

import com.cy.core.exception.CYException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis 上下文窗口存储（stateful 模式默认后端）。
 * <p>
 * 键布局：快照 {@code orrery:mem:ctx:{sessionId}}（String JSON，TTL 滑动续期）；
 * 活跃锁 {@code orrery:mem:ctx:lock:{sessionId}}（String，TTL 兜底防会话泄漏死锁）。
 * save 经 Lua 脚本原子完成「GET → 比对 version → SET 新值 + TTL」。
 * </p>
 *
 * @author pcy
 */
public class RedisContextWindowStore implements ContextWindowStore {

    private static final Logger log = LoggerFactory.getLogger(RedisContextWindowStore.class);

    /** 快照键前缀。 */
    private static final String KEY_PREFIX = "orrery:mem:ctx:";

    /** 活跃锁键前缀。 */
    private static final String LOCK_PREFIX = "orrery:mem:ctx:lock:";

    /** 活跃锁 TTL（兜底：会话异常退出后锁自动失效；远长于正常 run 时长）。 */
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    /** CAS 脚本：ARGV[1]=期望版本，ARGV[2]=新 JSON，ARGV[3]=TTL 毫秒。 */
    private static final String CAS_SCRIPT =
            "local cur = redis.call('GET', KEYS[1]) "
                    + "if cur then "
                    + "  local ok, obj = pcall(cjson.decode, cur) "
                    + "  if ok and tostring(obj['version']) ~= ARGV[1] then return 0 end "
                    + "elseif ARGV[1] ~= '0' then return 0 end "
                    + "redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]) "
                    + "return 1";

    /** Redisson 客户端。 */
    private final RedissonClient redisson;

    /** JSON 编解码。 */
    private final ObjectMapper objectMapper;

    /** 快照 TTL（滑动续期）。 */
    private final Duration ttl;

    /**
     * 构造 Redis 窗口存储。
     *
     * @param redisson Redisson 客户端
     * @param ttl      快照 TTL（每次写回滑动续期）
     */
    public RedisContextWindowStore(RedissonClient redisson, Duration ttl) {
        this.redisson = redisson;
        this.ttl = ttl;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public Optional<ContextWindow> load(String sessionId) {
        RBucket<String> bucket = redisson.getBucket(KEY_PREFIX + sessionId, StringCodec.INSTANCE);
        String json = bucket.get();
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, ContextWindow.class));
        } catch (JsonProcessingException e) {
            // 快照损坏按空窗口处理，但删除坏数据防止每次 open 都读到它
            log.warn("上下文窗口快照损坏（按空窗口处理并清除）: sessionId={}", sessionId);
            bucket.delete();
            return Optional.empty();
        }
    }

    @Override
    public boolean save(String sessionId, ContextWindow window, long expectedVersion) {
        try {
            String json = objectMapper.writeValueAsString(window);
            Long result = redisson.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE, CAS_SCRIPT, RScript.ReturnType.INTEGER,
                    List.of(KEY_PREFIX + sessionId),
                    String.valueOf(expectedVersion), json, String.valueOf(ttl.toMillis()));
            return result != null && result == 1L;
        } catch (JsonProcessingException | RuntimeException e) {
            throw new CYException(50616, e.getMessage());
        }
    }

    @Override
    public boolean tryAcquire(String sessionId) {
        RBucket<String> lock = redisson.getBucket(LOCK_PREFIX + sessionId, StringCodec.INSTANCE);
        return lock.trySet("1", LOCK_TTL.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void release(String sessionId) {
        redisson.getBucket(LOCK_PREFIX + sessionId, StringCodec.INSTANCE).delete();
    }

    @Override
    public boolean available() {
        return true;
    }
}
