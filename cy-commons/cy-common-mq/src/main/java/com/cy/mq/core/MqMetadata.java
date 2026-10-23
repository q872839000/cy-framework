package com.cy.mq.core;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MQ 消息元数据。
 * <p>
 * 表示一条 MQ 消息的非业务部分：消息 ID、发布时间、链路追踪、自定义头部等。
 * 元数据通过底层 broker 的原生通道传输（Kafka headers / Redis Stream
 * 保留字段），不与业务 payload 混用同一片字节，因此：
 * </p>
 * <ul>
 *     <li>外部系统消息可以没有 metadata，框架会自动合成。</li>
 *     <li>内部消息携带的 metadata 不会污染业务 payload。</li>
 *     <li>跨语言消费方可以只关心 payload，不需要理解本组件的元数据结构。</li>
 * </ul>
 * <p>
 * <b>本类不再包含 payload 字段。</b>业务消息体由 {@link MqMessage#payload()} 承载。
 * </p>
 *
 * @author pcy
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class MqMetadata {

    /**
     * 消息 ID。
     * <p>
     * 框架在发布时生成（UUID）；从 broker 入站时若 metadata 携带
     * 则保留原值，否则使用 broker 原生 ID（{@link #nativeMessageId}）填充。
     * </p>
     */
    private String messageId;

    /**
     * 主题名称。
     * <p>
     * 入站消息的 topic 由 driver 填入；不通过 broker metadata 传输。
     * </p>
     */
    private String topic;

    /**
     * 业务消息键。
     * <p>
     * Kafka 中影响 partition 路由；其他 driver 仅作为业务标识。
     * </p>
     */
    private String key;

    /**
     * 消息发布时间戳（毫秒 since epoch）。
     */
    private long timestamp;

    /**
     * 投递模式。
     * <p>仅出站时由发布方指定；入站时由 driver 据来源填入，可能为 {@code null}。</p>
     */
    private MqDeliveryMode deliveryMode;

    /**
     * 链路追踪 ID。
     * <p>同一条业务链路上的所有 MQ 消息共享相同的 traceId。</p>
     */
    private String traceId;

    /**
     * 当前 Span ID。
     * <p>表示发布该消息时所在的 span；消费端会将其作为 {@code parentSpanId} 用于构建调用链。</p>
     */
    private String spanId;

    /**
     * 父 Span ID。
     * <p>指向上游调用方的 spanId，跨服务消息常用。</p>
     */
    private String parentSpanId;

    /**
     * 自定义头部。
     * <p>
     * 除框架保留头部（{@link MqHeaders#PREFIX} 前缀）以外的业务自定义头部，
     * 由发布端写入、消费端读取。所有 driver 都按 broker 能力尽量保留这些头部。
     * </p>
     * <p>使用 {@link LinkedHashMap} 保留写入顺序。</p>
     */
    @Builder.Default
    private Map<String, String> headers = new LinkedHashMap<>();

    /**
     * 入站来源 driver。
     * <p>仅在 driver 构造入站 metadata 时写入；出站时不使用。</p>
     */
    private MqDriverType source;

    /**
     * 底层 broker 的原生消息 ID。
     * <p>
     * 例如：Kafka 为 {@code partition-offset}，Redis Stream 为 entry id，
     * Redis Pub/Sub 没有原生 ID 时由框架生成 UUID。
     * 仅在入站时由 driver 填入。
     * </p>
     */
    private String nativeMessageId;

    /**
     * 标记该 metadata 是否由消费 runtime 合成（即外部系统消息）。
     * <p>
     * {@code true} 表示入站消息没有携带框架元数据，metadata 中的 traceId
     * 等字段是消费端新生成的；{@code false} 表示来自内部发布、metadata 完整还原。
     * </p>
     */
    private boolean synthetic;

    /**
     * 创建一个新的出站 metadata，自动生成 messageId 与时间戳。
     *
     * @param topic 主题
     * @param key   消息键，可为空
     * @return 新 metadata
     */
    public static MqMetadata newOutbound(String topic, String key) {
        return MqMetadata.builder()
                .messageId(UUID.randomUUID().toString())
                .topic(topic)
                .key(key)
                .timestamp(Instant.now().toEpochMilli())
                .headers(new LinkedHashMap<>())
                .build();
    }

    /**
     * 安全获取 headers，避免空指针。
     *
     * @return 当前 headers，若为空则返回新的空 map（不会写回字段）
     */
    public Map<String, String> safeHeaders() {
        return headers == null ? new LinkedHashMap<>() : headers;
    }
}
