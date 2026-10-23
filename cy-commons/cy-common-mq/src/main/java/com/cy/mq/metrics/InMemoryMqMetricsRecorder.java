package com.cy.mq.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存 MQ 指标记录器。
 * <p>
 * 在内存中维护全局指标计数器，用于快速查询和兜底场景。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
public class InMemoryMqMetricsRecorder implements MqMetricsRecorder {

    // ==================== 发布指标 ====================

    private final AtomicLong publishSuccessCount = new AtomicLong(0);
    private final AtomicLong publishFailureCount = new AtomicLong(0);
    private final AtomicLong lastPublishDuration = new AtomicLong(0);

    // ==================== 消费指标 ====================

    private final AtomicLong consumeSuccessCount = new AtomicLong(0);
    private final AtomicLong consumeFailureCount = new AtomicLong(0);
    private final AtomicLong lastConsumeDuration = new AtomicLong(0);

    @Override
    public void recordPublishSuccess(MqMetricsContext context) {
        publishSuccessCount.incrementAndGet();
    }

    @Override
    public void recordPublishFailure(MqMetricsContext context) {
        publishFailureCount.incrementAndGet();
    }

    @Override
    public void recordPublishDuration(MqMetricsContext context, long durationMs) {
        lastPublishDuration.set(durationMs);
    }

    @Override
    public void recordConsumeSuccess(MqMetricsContext context) {
        consumeSuccessCount.incrementAndGet();
    }

    @Override
    public void recordConsumeFailure(MqMetricsContext context) {
        consumeFailureCount.incrementAndGet();
    }

    @Override
    public void recordConsumeDuration(MqMetricsContext context, long durationMs) {
        lastConsumeDuration.set(durationMs);
    }

    // ==================== 查询方法 ====================

    public long getPublishSuccessCount() {
        return publishSuccessCount.get();
    }

    public long getPublishFailureCount() {
        return publishFailureCount.get();
    }

    public long getLastPublishDuration() {
        return lastPublishDuration.get();
    }

    public double getPublishFailureRate() {
        long total = publishSuccessCount.get() + publishFailureCount.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) publishFailureCount.get() / total;
    }

    public long getConsumeSuccessCount() {
        return consumeSuccessCount.get();
    }

    public long getConsumeFailureCount() {
        return consumeFailureCount.get();
    }

    public long getLastConsumeDuration() {
        return lastConsumeDuration.get();
    }

    public double getConsumeFailureRate() {
        long total = consumeSuccessCount.get() + consumeFailureCount.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) consumeFailureCount.get() / total;
    }

    public void reset() {
        publishSuccessCount.set(0);
        publishFailureCount.set(0);
        lastPublishDuration.set(0);
        consumeSuccessCount.set(0);
        consumeFailureCount.set(0);
        lastConsumeDuration.set(0);
    }
}
