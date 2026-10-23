package com.cy.mq.driver.local;

import com.cy.mq.core.ConsumeDecision;
import com.cy.mq.core.ConsumeStatus;
import com.cy.mq.core.MqDriver;
import com.cy.mq.core.MqDriverType;
import com.cy.mq.core.MqHeaders;
import com.cy.mq.core.MqInboundRecord;
import com.cy.mq.core.MqOutboundRecord;
import com.cy.mq.core.MqRecordHandler;
import com.cy.mq.core.MqSubscribeSpec;
import com.cy.mq.core.MqSubscription;
import com.cy.mq.exception.MqPublishException;
import com.cy.mq.metrics.MqMetrics;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地队列驱动（进程内发布订阅）。
 * <p>
 * 适用于：
 * </p>
 * <ul>
 *     <li>本地开发调试。</li>
 *     <li>单体部署。</li>
 *     <li>无需跨进程投递的异步解耦场景。</li>
 * </ul>
 * <p>
 * 该驱动不具备跨进程能力，不保证消息持久化；进程退出后未投递的异步任务会丢失。
 * </p>
 * <p>
 * <b>实现要点：</b>同一进程内，出站记录与入站记录共享同一片字节，无需任何序列化。
 * driver 仅负责调度（同步 / 异步线程池）与订阅者列表管理。
 * </p>
 *
 * @author pcy
 */
@Slf4j
public class LocalMqDriver implements MqDriver {

    /**
     * 是否异步投递。
     */
    private final boolean async;

    /**
     * 异步投递执行器（Spring 管理）。
     */
    private final Executor executor;

    /**
     * 订阅者列表（按 topic 隔离）。
     */
    private final ConcurrentMap<String, CopyOnWriteArrayList<LocalSubscription>> subscriberMap =
            new ConcurrentHashMap<>();

    /**
     * 构造函数。
     *
     * @param async    是否异步投递
     * @param executor 异步投递执行器
     */
    public LocalMqDriver(boolean async, Executor executor) {
        this.async = async;
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Override
    public MqDriverType type() {
        return MqDriverType.LOCAL;
    }

    @Override
    public void publish(MqOutboundRecord record) {
        if (record == null || record.getTopic() == null) {
            return;
        }

        String messageId = resolveMessageId(record.safeHeaders());
        long startTime = System.currentTimeMillis();
        try {
            List<LocalSubscription> subscriptions = subscriberMap.get(record.getTopic());
            if (subscriptions == null || subscriptions.isEmpty()) {
                MqMetrics.recordPublishSuccess(record.getTopic(), type().name(), messageId);
                MqMetrics.recordPublishDuration(record.getTopic(), type().name(), messageId,
                        System.currentTimeMillis() - startTime);
                return;
            }

            for (LocalSubscription subscription : subscriptions) {
                if (subscription.isClosed()) {
                    continue;
                }
                deliver(subscription, record, messageId);
            }

            MqMetrics.recordPublishSuccess(record.getTopic(), type().name(), messageId);
            MqMetrics.recordPublishDuration(record.getTopic(), type().name(), messageId,
                    System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            MqMetrics.recordPublishFailure(record.getTopic(), type().name(), messageId);
            MqMetrics.recordPublishDuration(record.getTopic(), type().name(), messageId,
                    System.currentTimeMillis() - startTime);
            throw e;
        }
    }

    @Override
    public MqSubscription subscribe(MqSubscribeSpec spec, MqRecordHandler handler) {
        if (spec == null || spec.getTopic() == null) {
            throw new IllegalArgumentException("MqSubscribeSpec.topic must not be null");
        }
        Objects.requireNonNull(handler, "handler must not be null");

        LocalSubscription subscription = new LocalSubscription(spec, handler);
        subscriberMap.computeIfAbsent(spec.getTopic(), k -> new CopyOnWriteArrayList<>()).add(subscription);
        return subscription;
    }

    /**
     * 投递一条记录给单个订阅者。
     */
    private void deliver(LocalSubscription subscription, MqOutboundRecord record, String messageId) {
        if (!async) {
            subscription.consume(record, messageId);
            return;
        }

        try {
            executor.execute(() -> subscription.consume(record, messageId));
        } catch (RejectedExecutionException e) {
            // 线程池拒绝时降级为同步执行，确保消息不丢失。
            log.warn("本地队列线程池拒绝执行，降级为同步执行: topic={}, msgId={}",
                    record.getTopic(), messageId);
            subscription.consume(record, messageId);
        } catch (Exception e) {
            log.error("本地队列投递失败: topic={}, msgId={}", record.getTopic(), messageId, e);
            throw new MqPublishException(record.getTopic(), messageId, "本地队列投递失败", e);
        }
    }

    /**
     * 解析消息 ID。
     */
    private static String resolveMessageId(Map<String, String> headers) {
        String id = headers.get(MqHeaders.MESSAGE_ID);
        return id != null && !id.isBlank() ? id : UUID.randomUUID().toString();
    }

    /**
     * 把出站记录转换为入站记录。
     * <p>
     * 同进程内，body 与 headers 直接共享引用，避免任何拷贝；
     * driver 来源、nativeMessageId 由 driver 写入。
     * </p>
     */
    private MqInboundRecord toInbound(MqOutboundRecord out, String groupId, String messageId) {
        Map<String, String> headers = out.getHeaders() == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(out.getHeaders());
        long timestamp = parseTimestamp(headers.get(MqHeaders.TIMESTAMP));
        return MqInboundRecord.builder()
                .driver(type())
                .topic(out.getTopic())
                .groupId(groupId)
                .key(out.getKey())
                .body(out.getBody())
                .headers(headers)
                .nativeMessageId(messageId)
                .timestamp(timestamp)
                .attributes(Collections.emptyMap())
                .build();
    }

    private static long parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return System.currentTimeMillis();
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return System.currentTimeMillis();
        }
    }

    /**
     * 本地订阅句柄。
     */
    private class LocalSubscription implements MqSubscription {

        /**
         * 订阅参数。
         */
        private final MqSubscribeSpec spec;

        /**
         * 入站处理器。
         */
        private final MqRecordHandler handler;

        /**
         * 是否已关闭。
         */
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private LocalSubscription(MqSubscribeSpec spec, MqRecordHandler handler) {
            this.spec = spec;
            this.handler = handler;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            // 原子性地从订阅表移除自身；空列表清理。
            subscriberMap.computeIfPresent(spec.getTopic(), (k, list) -> {
                list.remove(this);
                return list.isEmpty() ? null : list;
            });
        }

        boolean isClosed() {
            return closed.get();
        }

        /**
         * 同步消费一条出站记录。
         */
        void consume(MqOutboundRecord out, String messageId) {
            if (closed.get()) {
                return;
            }
            long startTime = System.currentTimeMillis();
            String topic = out.getTopic();
            String driverName = type().name();
            String groupId = spec.getGroupId();

            MqInboundRecord inbound = toInbound(out, groupId, messageId);
            try {
                ConsumeDecision decision = handler.handle(inbound);
                if (decision == null) {
                    decision = ConsumeDecision.success();
                }
                if (decision.status() == ConsumeStatus.SUCCESS) {
                    MqMetrics.recordConsumeSuccess(topic, driverName, groupId, messageId);
                } else {
                    // 本地驱动没有 ACK 概念，统一记录失败用于诊断。
                    MqMetrics.recordConsumeFailure(topic, driverName, groupId, messageId);
                    log.warn("本地队列消费返回非 SUCCESS 决策: topic={}, msgId={}, status={}",
                            topic, messageId, decision.status());
                }
            } catch (Exception e) {
                MqMetrics.recordConsumeFailure(topic, driverName, groupId, messageId);
                log.error("本地队列消费抛出未受控异常: topic={}, msgId={}", topic, messageId, e);
            } finally {
                MqMetrics.recordConsumeDuration(topic, driverName, groupId, messageId,
                        System.currentTimeMillis() - startTime);
            }
        }
    }
}
