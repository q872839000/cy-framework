package com.cy.mq.core;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import java.time.Duration;

/**
 * 订阅参数规范。
 * <p>
 * 由消费 runtime 在向 driver 注册订阅时提供，描述本次订阅在 driver 层
 * 的工作参数（topic / groupId / 批量与否 / 拉取参数等）。driver 不持有
 * payload 类型与业务回调；这些细节封装在 {@link MqRecordHandler} 内部。
 * </p>
 *
 * @author pcy
 */
@Getter
@Builder
@RequiredArgsConstructor
@ToString
public class MqSubscribeSpec {

    /**
     * 主题。
     */
    private final String topic;

    /**
     * 消费组。
     * <p>
     * Kafka / Redis Stream 必须指定；Redis Pub/Sub / Local 可为 {@code null}。
     * </p>
     */
    private final String groupId;

    /**
     * 是否批量消费。
     * <p>{@code true} 时 driver 调用 {@link MqRecordHandler#handleBatch(java.util.List)}。</p>
     */
    private final boolean batch;

    /**
     * 单次批量大小（仅 batch=true 生效）。
     * <p>{@code null} 表示使用 driver 默认值。</p>
     */
    private final Integer batchSize;

    /**
     * 拉取超时（适用于 Kafka / Redis Stream）。
     * <p>{@code null} 表示使用 driver 默认值。</p>
     */
    private final Duration pollTimeout;
}
