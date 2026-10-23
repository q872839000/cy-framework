package com.cy.mq.core;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import java.util.Collections;
import java.util.Map;

/**
 * 入站原始记录。
 * <p>
 * 表示从底层 broker 拉取到的一条原始消息，是 driver 与 codec / runtime
 * 之间的统一交换格式。driver 不负责反序列化业务 payload，也不负责
 * 解析 metadata，只负责把 broker 原生记录映射为本结构。
 * </p>
 * <p>
 * 关键字段：
 * </p>
 * <ul>
 *     <li>{@link #body()}：业务 payload 的原始字节，永远只是 payload，<b>不</b>包含 framework metadata。</li>
 *     <li>{@link #headers()}：framework metadata（{@code __cy_mq_*} 命名空间）与业务自定义头部的合集。</li>
 *     <li>{@link #attributes()}：driver 特定的非语义信息（如 Kafka partition/offset，Stream entryId），仅用于诊断与追踪。</li>
 * </ul>
 *
 * @author pcy
 */
@Getter
@Builder
@RequiredArgsConstructor
@ToString
public class MqInboundRecord {

    /**
     * 来源 driver。
     */
    private final MqDriverType driver;

    /**
     * 主题。
     */
    private final String topic;

    /**
     * 消费组（无消费组时为 {@code null}，例如 Pub/Sub 广播）。
     */
    private final String groupId;

    /**
     * 业务消息键（可为 {@code null}）。
     * <p>
     * Kafka：来自 {@code ConsumerRecord#key()}。<br>
     * Redis：来自 {@link MqHeaders#KEY} 或为 {@code null}。
     * </p>
     */
    private final String key;

    /**
     * 业务 payload 的原始字节。
     * <p>
     * 永远是「业务消息体」本身，不含 framework metadata。
     * 字节级表达便于支持纯文本、JSON、二进制等任意格式。
     * </p>
     */
    private final byte[] body;

    /**
     * 头部集合。
     * <p>
     * 包含 framework 保留头（{@link MqHeaders#PREFIX} 前缀）与业务自定义头部。
     * 永远非空（至少为空 map）。
     * </p>
     */
    private final Map<String, String> headers;

    /**
     * 底层 broker 的原生消息 ID。
     * <p>
     * Kafka：{@code partition-offset}；Redis Stream：entry id；Pub/Sub：框架生成 UUID。
     * </p>
     */
    private final String nativeMessageId;

    /**
     * 消息发布时间戳（毫秒）。
     * <p>broker 原生时间戳；framework metadata 中的时间戳由 codec 单独填入。</p>
     */
    private final long timestamp;

    /**
     * driver 特定的诊断属性。
     * <p>
     * 仅用于日志、metrics、调试输出，不应承载业务语义。例如 Kafka partition、
     * offset、Stream record id 等。
     * </p>
     */
    private final Map<String, Object> attributes;

    /**
     * 安全获取 headers。
     *
     * @return headers（永远非空）
     */
    public Map<String, String> safeHeaders() {
        return headers == null ? Collections.emptyMap() : headers;
    }

    /**
     * 安全获取 attributes。
     *
     * @return attributes（永远非空）
     */
    public Map<String, Object> safeAttributes() {
        return attributes == null ? Collections.emptyMap() : attributes;
    }
}
