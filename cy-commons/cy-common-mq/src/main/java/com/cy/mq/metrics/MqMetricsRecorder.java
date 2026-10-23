package com.cy.mq.metrics;

/**
 * MQ 指标记录器接口。
 * <p>
 * 定义统一的指标记录接口，支持多种实现（内存、Micrometer 等）。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
public interface MqMetricsRecorder {

    /**
     * 记录发布成功。
     *
     * @param context 指标上下文
     */
    void recordPublishSuccess(MqMetricsContext context);

    /**
     * 记录发布失败。
     *
     * @param context 指标上下文
     */
    void recordPublishFailure(MqMetricsContext context);

    /**
     * 记录发布耗时。
     *
     * @param context    指标上下文
     * @param durationMs 耗时（毫秒）
     */
    void recordPublishDuration(MqMetricsContext context, long durationMs);

    /**
     * 记录消费成功。
     *
     * @param context 指标上下文
     */
    void recordConsumeSuccess(MqMetricsContext context);

    /**
     * 记录消费失败。
     *
     * @param context 指标上下文
     */
    void recordConsumeFailure(MqMetricsContext context);

    /**
     * 记录消费耗时。
     *
     * @param context    指标上下文
     * @param durationMs 耗时（毫秒）
     */
    void recordConsumeDuration(MqMetricsContext context, long durationMs);
}
