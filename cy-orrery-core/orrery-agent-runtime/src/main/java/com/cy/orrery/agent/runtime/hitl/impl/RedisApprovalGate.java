// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.hitl.impl;

import com.cy.orrery.agent.runtime.hitl.ApprovalDecision;
import com.cy.orrery.agent.runtime.hitl.ApprovalGate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis 分布式审批等待门：跨实例唤醒 + 共享存储冷启动兜底。
 * <p>
 * 多副本部署下「持有运行的实例」与「接收审批回调的实例」往往不同，本实现把
 * 决定路径拆成两跳：
 * <ul>
 *     <li><b>热路径（跨实例唤醒）</b>：decide 把决定写 Redis（首决定 setIfAbsent、
 *         TTL 与审批生命周期对齐）并经 RTopic 广播；持有运行的本实例订阅到广播后
 *         完成内存 future，唤醒等待线程；</li>
 *     <li><b>冷路径（兜底）</b>：register 时先查 Redis 已有决定（决定早于本实例
 *         恢复到达）直接完成 future；awaitDecision 分片等待（每片超时即查一次
 *         Redis）——广播丢失 / 订阅建立前的决定最多延迟一个分片即可取回，
 *         不再等满整个超时。</li>
 * </ul>
 * 决定 JSON 值存 {@link RBucket}（StringCodec，与检查点存储同选型），
 * 广播消息即同一 JSON 文本（无需独立消息格式）。
 * 资源：构造时订阅 topic，{@link #close} 反订阅（宿主容器销毁时调用）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/28
 */
public class RedisApprovalGate implements ApprovalGate, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisApprovalGate.class);

    /** Redis 兜底轮询分片（毫秒）：分片超时即查 Redis，广播丢失的决定不等到整体超时。 */
    private static final long REDIS_POLL_SLICE_MILLIS = 5000L;

    /** 默认决定 key 前缀。 */
    public static final String DEFAULT_KEY_PREFIX = "orrery:agent-runtime:approval:decision:";

    /** 默认广播主题名。 */
    public static final String DEFAULT_TOPIC = "orrery:agent-runtime:approval:decisions";

    /** 决定 TTL 默认值（7 天，与挂起检查点 TTL 对齐：决定必须活到它要唤醒的挂起运行）。 */
    public static final long DEFAULT_DECISION_TTL_MILLIS = 7L * 24 * 3600 * 1000;

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final String topicName;
    private final long decisionTtlMillis;

    /** 本实例等待中的审批单（approvalId → future；跨实例经 topic / Redis 完成）。 */
    private final Map<String, CompletableFuture<ApprovalDecision>> pending =
            new ConcurrentHashMap<>();

    /** 广播订阅句柄（订阅失败降级单机模式时为 null，close 判空）。 */
    private final Integer topicListenerId;

    /**
     * 构造分布式审批门（默认前缀 / 主题 / 7 天决定 TTL）。
     *
     * @param redisson     Redisson 客户端
     * @param objectMapper JSON 编解码器
     */
    public RedisApprovalGate(RedissonClient redisson, ObjectMapper objectMapper) {
        this(redisson, objectMapper, DEFAULT_KEY_PREFIX, DEFAULT_TOPIC,
                DEFAULT_DECISION_TTL_MILLIS);
    }

    /**
     * 构造分布式审批门。
     * <p>
     * Redis 不可达时订阅失败不阻断装配——门降级为单机模式（本实例决定唤醒仍可用，
     * 跨实例广播与冷启动兜底待 Redis 恢复后随新实例生效）。
     * </p>
     *
     * @param redisson          Redisson 客户端
     * @param objectMapper      JSON 编解码器
     * @param keyPrefix         决定 key 前缀
     * @param topicName         广播主题名
     * @param decisionTtlMillis 决定 TTL 毫秒（≤0 不过期）
     */
    public RedisApprovalGate(RedissonClient redisson, ObjectMapper objectMapper,
                             String keyPrefix, String topicName, long decisionTtlMillis) {
        this.redisson = Objects.requireNonNull(redisson, "redisson");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.keyPrefix = keyPrefix;
        this.topicName = topicName;
        this.decisionTtlMillis = decisionTtlMillis;
        Integer listenerId = null;
        try {
            listenerId = topic().addListener(String.class,
                    (channel, decisionJson) -> onRemoteDecision(decisionJson));
            log.info("Redis 审批门已订阅决定广播 topic={}", topicName);
        } catch (RuntimeException e) {
            log.warn("Redis 审批门订阅失败，降级单机模式工作: {}", e.getMessage());
        }
        this.topicListenerId = listenerId;
    }

    @Override
    public void register(String approvalId) {
        Objects.requireNonNull(approvalId, "approvalId");
        CompletableFuture<ApprovalDecision> future =
                pending.computeIfAbsent(approvalId, id -> new CompletableFuture<>());
        // 冷启动兜底：决定可能在重启前已写入 Redis（topic 广播早已错过），注册即查
        ApprovalDecision existing = readDecision(approvalId);
        if (existing != null) {
            future.complete(existing);
        }
    }

    @Override
    public ApprovalDecision awaitDecision(String approvalId, long timeoutMillis)
            throws InterruptedException {
        CompletableFuture<ApprovalDecision> future = pending.get(approvalId);
        if (future == null) {
            return ApprovalDecision.timedOut(approvalId);
        }
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            long slice = Math.min(REDIS_POLL_SLICE_MILLIS, Math.max(remaining, 1));
            try {
                ApprovalDecision decision = future.get(slice, TimeUnit.MILLISECONDS);
                pending.remove(approvalId, future);
                return decision;
            } catch (TimeoutException e) {
                // 分片超时即查 Redis（事实源）：广播丢失 / 订阅建立前落库的决定
                // 最多延迟一个分片取回，不等满整体超时
                ApprovalDecision lateArrival = readDecision(approvalId);
                if (lateArrival != null) {
                    pending.remove(approvalId, future);
                    return lateArrival;
                }
                if (System.currentTimeMillis() >= deadline) {
                    pending.remove(approvalId, future);
                    return ApprovalDecision.timedOut(approvalId);
                }
            } catch (InterruptedException e) {
                pending.remove(approvalId, future);
                throw e;
            } catch (ExecutionException | CancellationException e) {
                // abort / 广播异常完成：按拒绝兜底（与进程内实现语义一致）
                pending.remove(approvalId, future);
                return ApprovalDecision.aborted(approvalId);
            }
        }
    }

    @Override
    public boolean decide(String approvalId, boolean approved, String decidedBy, String comment) {
        ApprovalDecision decision = approved
                ? ApprovalDecision.approve(approvalId, decidedBy)
                : ApprovalDecision.reject(approvalId, decidedBy, comment);
        String decisionJson = writeDecision(decision);
        // 首决定落库（setIfAbsent）：重复决定返回 false；TTL 限定决定生命周期。
        // Redis 写失败不抛出——本地唤醒与广播仍尽力执行，跨实例兜底降级（等待方超时终查会再试 Redis）
        boolean recorded;
        try {
            if (decisionTtlMillis > 0) {
                recorded = bucket(approvalId).setIfAbsent(decisionJson,
                        Duration.ofMillis(decisionTtlMillis));
            } else {
                recorded = bucket(approvalId).setIfAbsent(decisionJson);
            }
        } catch (RuntimeException e) {
            log.warn("审批决定落库失败（降级尽力唤醒）approvalId={}: {}", approvalId, e.getMessage());
            recorded = true;
        }
        if (!recorded) {
            return false;
        }
        // 广播跨实例唤醒（本实例也可能持有等待者，本地 future 直接完成）
        try {
            boolean published = topic().publish(decisionJson) > 0;
            if (!published) {
                // 无订阅者不视为失败：等待方 register / 超时终查会从 Redis 取回
                log.debug("审批决定广播无订阅者 approvalId={}（Redis 已落库兜底）", approvalId);
            }
        } catch (RuntimeException e) {
            log.warn("审批决定广播失败（Redis 已落库兜底）approvalId={}: {}", approvalId, e.getMessage());
        }
        completeLocal(approvalId, decision);
        return true;
    }

    @Override
    public void abort(String approvalId) {
        CompletableFuture<ApprovalDecision> future = pending.remove(approvalId);
        if (future != null) {
            future.cancel(false);
        }
        // 不删 Redis 决定：abort 是本实例的运行取消，共享决定记录由 TTL 自然过期
    }

    /**
     * 反订阅广播主题（宿主容器销毁时调用；单机降级模式下为空操作）。
     */
    @Override
    public void close() {
        if (topicListenerId != null) {
            topic().removeListener(topicListenerId);
        }
    }

    /**
     * 广播到达：完成本实例等待中的 future（不持有该审批单则忽略）。
     *
     * @param decisionJson 决定 JSON
     */
    private void onRemoteDecision(String decisionJson) {
        try {
            ApprovalDecision decision = objectMapper.readValue(decisionJson, ApprovalDecision.class);
            completeLocal(decision.approvalId(), decision);
        } catch (JsonProcessingException e) {
            log.error("审批决定广播处理失败 message={}", decisionJson, e);
        }
    }

    /**
     * 完成本地等待 future（不存在 / 已完成则忽略）。
     *
     * @param approvalId 审批单 ID
     * @param decision   决定
     */
    private void completeLocal(String approvalId, ApprovalDecision decision) {
        CompletableFuture<ApprovalDecision> future = pending.get(approvalId);
        if (future != null) {
            future.complete(decision);
        }
    }

    /**
     * 读 Redis 已有决定（不存在 / 反序列化失败返回 null）。
     *
     * @param approvalId 审批单 ID
     * @return 决定；无为 null
     */
    private ApprovalDecision readDecision(String approvalId) {
        String json;
        try {
            json = bucket(approvalId).get();
        } catch (RuntimeException e) {
            log.warn("审批决定读取失败 approvalId={}: {}", approvalId, e.getMessage());
            return null;
        }
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ApprovalDecision.class);
        } catch (JsonProcessingException e) {
            log.warn("审批决定反序列化失败 approvalId={}: {}", approvalId, e.getMessage());
            return null;
        }
    }

    /**
     * 序列化决定。
     *
     * @param decision 决定
     * @return JSON 文本
     */
    private String writeDecision(ApprovalDecision decision) {
        try {
            return objectMapper.writeValueAsString(decision);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("审批决定序列化失败: " + decision.approvalId(), e);
        }
    }

    /**
     * 取决定字符串桶。
     *
     * @param approvalId 审批单 ID
     * @return 字符串桶
     */
    private RBucket<String> bucket(String approvalId) {
        return redisson.getBucket(keyPrefix + approvalId, StringCodec.INSTANCE);
    }

    /**
     * 取决定广播主题。
     *
     * @return 主题
     */
    private RTopic topic() {
        return redisson.getTopic(topicName, StringCodec.INSTANCE);
    }
}
