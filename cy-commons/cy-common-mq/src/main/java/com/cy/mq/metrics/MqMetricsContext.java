package com.cy.mq.metrics;

import lombok.Builder;
import lombok.Value;

/**
 * MQ 指标上下文。
 * <p>
 * 封装指标记录所需的所有维度信息，确保指标记录的完整性和一致性。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
@Value
@Builder
public class MqMetricsContext {

    /**
     * 主题名称。
     */
    String topic;

    /**
     * 驱动类型。
     */
    String driver;

    /**
     * 消费组 ID（消费端专用）。
     */
    String groupId;

    /**
     * 消息 ID。
     */
    String messageId;

    /**
     * 操作类型（publish/consume）。
     */
    OperationType operationType;

    /**
     * 操作类型枚举。
     */
    public enum OperationType {
        PUBLISH,
        CONSUME
    }

    /**
     * 创建发布指标上下文。
     */
    public static MqMetricsContext forPublish(String topic, String driver, String messageId) {
        return MqMetricsContext.builder()
                .topic(topic)
                .driver(driver)
                .messageId(messageId)
                .operationType(OperationType.PUBLISH)
                .build();
    }

    /**
     * 创建消费指标上下文。
     */
    public static MqMetricsContext forConsume(String topic, String driver, String groupId, String messageId) {
        return MqMetricsContext.builder()
                .topic(topic)
                .driver(driver)
                .groupId(groupId != null ? groupId : "default")
                .messageId(messageId)
                .operationType(OperationType.CONSUME)
                .build();
    }
}
