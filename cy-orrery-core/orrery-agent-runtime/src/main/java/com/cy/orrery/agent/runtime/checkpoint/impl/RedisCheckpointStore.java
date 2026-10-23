// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.checkpoint.impl;


import com.cy.orrery.agent.runtime.checkpoint.Checkpoint;
import com.cy.orrery.agent.runtime.checkpoint.CheckpointSerializer;
import com.cy.orrery.agent.runtime.checkpoint.CheckpointStore;
import com.cy.orrery.agent.runtime.hitl.HitlAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * Redis 检查点存储（生产实现，多副本共享）。
 * <p>
 * 每个运行一个 key（{@code keyPrefix + runId}），值为 JSON 文本
 * （{@link StringCodec}，避免 Redisson 默认编解码器对 record 的
 * JDK 序列化不可读问题）；TTL 到期自动清理，防长期挂起泄漏。
 * 挂起的运行在任意副本上都能经本存储恢复——多副本部署必须
 * 使用本实现替换进程内 {@link InMemoryCheckpointStore}。
 * </p>
 * <p>
 * <b>双 TTL</b>：常规检查点用 {@code ttlMillis}（默认 24h）；挂起运行
 * （属性表含 {@code hitl.suspendedState}）用 {@code suspendedTtlMillis}
 * （默认 7d）——审批等待可能横跨数天，短 TTL 会把挂起现场提前清掉。
 * </p>
 * <p>
 * <b>fencing</b>：{@link #saveIfNewer} 经 Lua 脚本原子完成「读现值 → 比较
 * revision → 条件写入 + TTL」（Redis 单线程执行脚本，无竞态窗口，等价 WATCH
 * 语义且无重试循环）；{@link #nextRevision} 用独立计数 key INCR（分布式单调）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/27
 */
public class RedisCheckpointStore implements CheckpointStore {

    /** 默认 key 前缀。 */
    public static final String DEFAULT_KEY_PREFIX = "orrery:agent-runtime:checkpoint:";

    /** 默认 TTL（24 小时，常规检查点）。 */
    public static final long DEFAULT_TTL_MILLIS = 24L * 3600 * 1000;

    /** 默认挂起检查点 TTL（7 天：审批等待现场须活过周末级等待）。 */
    public static final long DEFAULT_SUSPENDED_TTL_MILLIS = 7L * 24 * 3600 * 1000;

    /**
     * saveIfNewer 的 fencing 脚本：现值不存在则写入；现值 revision 低于新值则覆盖。
     * ARGV: [1]=新 revision（十进制文本）[2]=payload [3]=TTL 毫秒（≤0 永不过期）。
     * revision 自 payload JSON 顶层字段解析（cjson 为 Redis 内置库）。
     */
    private static final String SAVE_IF_NEWER_LUA = """
            local cur = redis.call('GET', KEYS[1])
            local newRev = tonumber(ARGV[1])
            if not cur then
                if tonumber(ARGV[3]) > 0 then
                    redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
                else
                    redis.call('SET', KEYS[1], ARGV[2])
                end
                return 1
            end
            local ok, obj = pcall(cjson.decode, cur)
            if not ok or not obj.revision or tonumber(obj.revision) >= newRev then
                return 0
            end
            if tonumber(ARGV[3]) > 0 then
                redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            else
                redis.call('SET', KEYS[1], ARGV[2])
            end
            return 1
            """;

    private final RedissonClient redisson;
    private final CheckpointSerializer serializer;
    private final String keyPrefix;
    private final long ttlMillis;
    private final long suspendedTtlMillis;

    /**
     * 构造 Redis 检查点存储（默认前缀 + 24h TTL + 7d 挂起 TTL）。
     *
     * @param redisson   Redisson 客户端
     * @param serializer 检查点序列化器
     */
    public RedisCheckpointStore(RedissonClient redisson, CheckpointSerializer serializer) {
        this(redisson, serializer, DEFAULT_KEY_PREFIX, DEFAULT_TTL_MILLIS,
                DEFAULT_SUSPENDED_TTL_MILLIS);
    }

    /**
     * 构造 Redis 检查点存储（单一 TTL，兼容旧装配）。
     *
     * @param redisson   Redisson 客户端
     * @param serializer 检查点序列化器
     * @param keyPrefix  key 前缀
     * @param ttlMillis  TTL 毫秒（≤0 表示不过期）
     */
    public RedisCheckpointStore(RedissonClient redisson, CheckpointSerializer serializer,
                                String keyPrefix, long ttlMillis) {
        this(redisson, serializer, keyPrefix, ttlMillis, ttlMillis);
    }

    /**
     * 构造 Redis 检查点存储（常规 / 挂起双 TTL）。
     *
     * @param redisson           Redisson 客户端
     * @param serializer         检查点序列化器
     * @param keyPrefix          key 前缀
     * @param ttlMillis          常规检查点 TTL 毫秒（≤0 不过期）
     * @param suspendedTtlMillis 挂起检查点 TTL 毫秒（≤0 不过期；建议 ≥ 审批等待上限）
     */
    public RedisCheckpointStore(RedissonClient redisson, CheckpointSerializer serializer,
                                String keyPrefix, long ttlMillis, long suspendedTtlMillis) {
        this.redisson = redisson;
        this.serializer = serializer;
        this.keyPrefix = keyPrefix;
        this.ttlMillis = ttlMillis;
        this.suspendedTtlMillis = suspendedTtlMillis;
    }

    @Override
    public Optional<Checkpoint> load(String runId) {
        String payload = bucket(runId).get();
        return payload == null ? Optional.empty() : Optional.of(serializer.deserialize(payload));
    }

    @Override
    public void save(Checkpoint checkpoint) {
        String payload = serializer.serialize(checkpoint);
        long ttl = ttlFor(checkpoint);
        if (ttl > 0) {
            bucket(checkpoint.runId()).set(payload, Duration.ofMillis(ttl));
        } else {
            bucket(checkpoint.runId()).set(payload);
        }
    }

    @Override
    public boolean saveIfNewer(Checkpoint checkpoint) {
        String payload = serializer.serialize(checkpoint);
        Long applied = redisson.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, SAVE_IF_NEWER_LUA, RScript.ReturnType.INTEGER,
                List.of(keyPrefix + checkpoint.runId()),
                Long.toString(checkpoint.revision()), payload,
                Long.toString(ttlFor(checkpoint)));
        return applied != null && applied == 1L;
    }

    @Override
    public long nextRevision(String runId) {
        // INCR 分布式单调：多副本同时分配也不重号（revision 全序是 fencing 的前提）
        return revisionCounter(runId).incrementAndGet();
    }

    @Override
    public void delete(String runId) {
        bucket(runId).delete();
        revisionCounter(runId).delete();
    }

    /**
     * 按「是否挂起运行」选用 TTL：属性表含挂起态契约的检查点用长 TTL。
     *
     * @param checkpoint 检查点
     * @return TTL 毫秒（≤0 不过期）
     */
    private long ttlFor(Checkpoint checkpoint) {
        boolean suspended = checkpoint.attributes() != null
                && checkpoint.attributes().containsKey(HitlAttributes.SUSPENDED_STATE_KEY_NAME);
        return suspended ? suspendedTtlMillis : ttlMillis;
    }

    /**
     * 按 runId 取字符串桶。
     *
     * @param runId 运行 ID
     * @return 字符串桶
     */
    private RBucket<String> bucket(String runId) {
        return redisson.getBucket(keyPrefix + runId, StringCodec.INSTANCE);
    }

    /**
     * 按 runId 取 revision 计数器（独立 key，随检查点同生命周期）。
     *
     * @param runId 运行 ID
     * @return 原子长整型计数器
     */
    private RAtomicLong revisionCounter(String runId) {
        return redisson.getAtomicLong(keyPrefix + "rev:" + runId);
    }
}
