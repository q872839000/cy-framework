package com.cy.mq.metrics;

/**
 * MQ 组件监控指标统一入口。
 * <p>
 * 提供统一的指标记录接口，同时记录到内存和 Micrometer（如果可用）。
 * 所有指标都带有完整的维度信息（topic/driver/groupId），支持多维度查询和聚合。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
public class MqMetrics {

    /**
     * 内存指标记录器（用于快速查询）。
     */
    private static final InMemoryMqMetricsRecorder inMemoryRecorder = new InMemoryMqMetricsRecorder();

    /**
     * Micrometer 指标记录器（可选，由自动配置注入）。
     */
    private static volatile MqMetricsRecorder micrometerRecorder;

    /**
     * 注册 Micrometer 指标记录器。
     * <p>
     * 由 MqMetricsAutoConfiguration 在 Micrometer 可用时自动调用。
     * </p>
     */
    public static void registerMicrometerRecorder(MqMetricsRecorder recorder) {
        micrometerRecorder = recorder;
    }

    // ==================== 发布指标方法 ====================

    /**
     * 记录发布成功。
     *
     * @param topic     主题名称
     * @param driver    驱动类型
     * @param messageId 消息 ID
     */
    public static void recordPublishSuccess(String topic, String driver, String messageId) {
        MqMetricsContext context = MqMetricsContext.forPublish(topic, driver, messageId);
        inMemoryRecorder.recordPublishSuccess(context);
        if (micrometerRecorder != null) {
            try {
                micrometerRecorder.recordPublishSuccess(context);
            } catch (Exception e) {
                // 指标记录失败不应影响业务
            }
        }
    }

    /**
     * 记录发布失败。
     *
     * @param topic     主题名称
     * @param driver    驱动类型
     * @param messageId 消息 ID
     */
    public static void recordPublishFailure(String topic, String driver, String messageId) {
        MqMetricsContext context = MqMetricsContext.forPublish(topic, driver, messageId);
        inMemoryRecorder.recordPublishFailure(context);
        if (micrometerRecorder != null) {
            try {
                micrometerRecorder.recordPublishFailure(context);
            } catch (Exception e) {
                // 指标记录失败不应影响业务
            }
        }
    }

    /**
     * 记录发布耗时。
     *
     * @param topic      主题名称
     * @param driver     驱动类型
     * @param messageId  消息 ID
     * @param durationMs 耗时（毫秒）
     */
    public static void recordPublishDuration(String topic, String driver, String messageId, long durationMs) {
        MqMetricsContext context = MqMetricsContext.forPublish(topic, driver, messageId);
        inMemoryRecorder.recordPublishDuration(context, durationMs);
        if (micrometerRecorder != null) {
            try {
                micrometerRecorder.recordPublishDuration(context, durationMs);
            } catch (Exception e) {
                // 指标记录失败不应影响业务
            }
        }
    }

    // ==================== 消费指标方法 ====================

    /**
     * 记录消费成功。
     *
     * @param topic     主题名称
     * @param driver    驱动类型
     * @param groupId   消费组 ID
     * @param messageId 消息 ID
     */
    public static void recordConsumeSuccess(String topic, String driver, String groupId, String messageId) {
        MqMetricsContext context = MqMetricsContext.forConsume(topic, driver, groupId, messageId);
        inMemoryRecorder.recordConsumeSuccess(context);
        if (micrometerRecorder != null) {
            try {
                micrometerRecorder.recordConsumeSuccess(context);
            } catch (Exception e) {
                // 指标记录失败不应影响业务
            }
        }
    }

    /**
     * 记录消费失败。
     *
     * @param topic     主题名称
     * @param driver    驱动类型
     * @param groupId   消费组 ID
     * @param messageId 消息 ID
     */
    public static void recordConsumeFailure(String topic, String driver, String groupId, String messageId) {
        MqMetricsContext context = MqMetricsContext.forConsume(topic, driver, groupId, messageId);
        inMemoryRecorder.recordConsumeFailure(context);
        if (micrometerRecorder != null) {
            try {
                micrometerRecorder.recordConsumeFailure(context);
            } catch (Exception e) {
                // 指标记录失败不应影响业务
            }
        }
    }

    /**
     * 记录消费耗时。
     *
     * @param topic      主题名称
     * @param driver     驱动类型
     * @param groupId    消费组 ID
     * @param messageId  消息 ID
     * @param durationMs 耗时（毫秒）
     */
    public static void recordConsumeDuration(String topic, String driver, String groupId, String messageId, long durationMs) {
        MqMetricsContext context = MqMetricsContext.forConsume(topic, driver, groupId, messageId);
        inMemoryRecorder.recordConsumeDuration(context, durationMs);
        if (micrometerRecorder != null) {
            try {
                micrometerRecorder.recordConsumeDuration(context, durationMs);
            } catch (Exception e) {
                // 指标记录失败不应影响业务
            }
        }
    }

    // ==================== 查询方法（从内存记录器获取） ====================

    /**
     * 获取发布成功计数。
     */
    public static long getPublishSuccessCount() {
        return inMemoryRecorder.getPublishSuccessCount();
    }

    /**
     * 获取发布失败计数。
     */
    public static long getPublishFailureCount() {
        return inMemoryRecorder.getPublishFailureCount();
    }

    /**
     * 获取最近一次发布耗时。
     */
    public static long getLastPublishDuration() {
        return inMemoryRecorder.getLastPublishDuration();
    }

    /**
     * 获取发布失败率（0.0 ~ 1.0）。
     */
    public static double getPublishFailureRate() {
        return inMemoryRecorder.getPublishFailureRate();
    }

    /**
     * 获取消费成功计数。
     */
    public static long getConsumeSuccessCount() {
        return inMemoryRecorder.getConsumeSuccessCount();
    }

    /**
     * 获取消费失败计数。
     */
    public static long getConsumeFailureCount() {
        return inMemoryRecorder.getConsumeFailureCount();
    }

    /**
     * 获取最近一次消费耗时。
     */
    public static long getLastConsumeDuration() {
        return inMemoryRecorder.getLastConsumeDuration();
    }

    /**
     * 获取消费失败率（0.0 ~ 1.0）。
     */
    public static double getConsumeFailureRate() {
        return inMemoryRecorder.getConsumeFailureRate();
    }

    /**
     * 重置所有指标（用于测试）。
     */
    public static void reset() {
        inMemoryRecorder.reset();
    }
}
