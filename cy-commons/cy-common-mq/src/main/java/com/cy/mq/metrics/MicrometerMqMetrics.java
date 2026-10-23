package com.cy.mq.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer 集成的 MQ 监控指标记录器。
 * <p>
 * 提供基于 Micrometer 的指标收集，支持 Prometheus、Grafana 等监控系统。
 * 所有指标都带有 topic/driver/group 标签，支持多维度查询和聚合。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
@Slf4j
public class MicrometerMqMetrics implements MqMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Counter> publishSuccessCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> publishFailureCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> publishTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> consumeSuccessCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> consumeFailureCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> consumeTimers = new ConcurrentHashMap<>();

    public MicrometerMqMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        log.info("Micrometer MQ 指标已启用");
    }

    @Override
    public void recordPublishSuccess(MqMetricsContext context) {
        getPublishSuccessCounter(context.getTopic(), context.getDriver()).increment();
    }

    @Override
    public void recordPublishFailure(MqMetricsContext context) {
        getPublishFailureCounter(context.getTopic(), context.getDriver()).increment();
    }

    @Override
    public void recordPublishDuration(MqMetricsContext context, long durationMs) {
        getPublishTimer(context.getTopic(), context.getDriver()).record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordConsumeSuccess(MqMetricsContext context) {
        getConsumeSuccessCounter(context.getTopic(), context.getDriver(), context.getGroupId()).increment();
    }

    @Override
    public void recordConsumeFailure(MqMetricsContext context) {
        getConsumeFailureCounter(context.getTopic(), context.getDriver(), context.getGroupId()).increment();
    }

    @Override
    public void recordConsumeDuration(MqMetricsContext context, long durationMs) {
        getConsumeTimer(context.getTopic(), context.getDriver(), context.getGroupId()).record(durationMs, TimeUnit.MILLISECONDS);
    }

    private Counter getPublishSuccessCounter(String topic, String driver) {
        return publishSuccessCounters.computeIfAbsent(topic + ":" + driver, key ->
                Counter.builder("mq.publish.success")
                        .tag("topic", topic)
                        .tag("driver", driver)
                        .description("MQ 发布成功计数")
                        .register(meterRegistry)
        );
    }

    private Counter getPublishFailureCounter(String topic, String driver) {
        return publishFailureCounters.computeIfAbsent(topic + ":" + driver, key ->
                Counter.builder("mq.publish.failure")
                        .tag("topic", topic)
                        .tag("driver", driver)
                        .description("MQ 发布失败计数")
                        .register(meterRegistry)
        );
    }

    private Timer getPublishTimer(String topic, String driver) {
        return publishTimers.computeIfAbsent(topic + ":" + driver, key ->
                Timer.builder("mq.publish.duration")
                        .tag("topic", topic)
                        .tag("driver", driver)
                        .description("MQ 发布耗时")
                        .register(meterRegistry)
        );
    }

    private Counter getConsumeSuccessCounter(String topic, String driver, String groupId) {
        String cacheKey = topic + ":" + driver + ":" + groupId;
        return consumeSuccessCounters.computeIfAbsent(cacheKey, key ->
                Counter.builder("mq.consume.success")
                        .tag("topic", topic)
                        .tag("driver", driver)
                        .tag("group", groupId)
                        .description("MQ 消费成功计数")
                        .register(meterRegistry)
        );
    }

    private Counter getConsumeFailureCounter(String topic, String driver, String groupId) {
        String cacheKey = topic + ":" + driver + ":" + groupId;
        return consumeFailureCounters.computeIfAbsent(cacheKey, key ->
                Counter.builder("mq.consume.failure")
                        .tag("topic", topic)
                        .tag("driver", driver)
                        .tag("group", groupId)
                        .description("MQ 消费失败计数")
                        .register(meterRegistry)
        );
    }

    private Timer getConsumeTimer(String topic, String driver, String groupId) {
        String cacheKey = topic + ":" + driver + ":" + groupId;
        return consumeTimers.computeIfAbsent(cacheKey, key ->
                Timer.builder("mq.consume.duration")
                        .tag("topic", topic)
                        .tag("driver", driver)
                        .tag("group", groupId)
                        .description("MQ 消费耗时")
                        .register(meterRegistry)
        );
    }
}
