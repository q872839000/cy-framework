package com.cy.mq.codec;

import com.cy.mq.core.MqHeaders;
import com.cy.mq.core.MqInboundRecord;
import com.cy.mq.core.MqMessage;
import com.cy.mq.core.MqMetadata;
import com.cy.mq.core.MqOutboundRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 默认 {@link MqMessageCodec} 实现。
 * <p>
 * 编码与解码规则严格对称，且不依赖任何「猜测式」协议识别：
 * </p>
 * <ol>
 *     <li>framework metadata 通过 {@link MqHeaders#PREFIX} 前缀的 headers 传输；</li>
 *     <li>业务自定义 headers 与 framework headers 共享同一个 headers map，但前缀不同；</li>
 *     <li>业务 payload 永远占据 body，不与 metadata 混用同一片字节。</li>
 * </ol>
 *
 * @author pcy
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultMqMessageCodec implements MqMessageCodec {

    /**
     * Payload 字节级编解码器。
     */
    private final MqPayloadCodec payloadCodec;

    @Override
    public MqOutboundRecord encode(MqMetadata metadata, Object payload) {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata must not be null");
        }
        if (metadata.getTopic() == null || metadata.getTopic().isBlank()) {
            throw new IllegalArgumentException("metadata.topic must not be blank");
        }

        // 1. 序列化 payload 为字节。
        byte[] body = payloadCodec.encode(payload);

        // 2. 拼装 headers：framework 保留头 + 业务自定义头。
        Map<String, String> headers = new LinkedHashMap<>();

        // 业务自定义头部优先写入；再覆盖 framework 头部，避免业务方误用保留头。
        if (metadata.getHeaders() != null) {
            for (Map.Entry<String, String> entry : metadata.getHeaders().entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                if (MqHeaders.isReserved(entry.getKey())) {
                    // 业务方禁止写入保留前缀；记录警告并丢弃，避免污染 framework metadata。
                    log.warn("MQ 发布忽略业务 header（命中 framework 保留前缀）: {}", entry.getKey());
                    continue;
                }
                headers.put(entry.getKey(), entry.getValue());
            }
        }

        // framework 保留头。
        putIfPresent(headers, MqHeaders.MESSAGE_ID, metadata.getMessageId());
        headers.put(MqHeaders.TIMESTAMP, Long.toString(metadata.getTimestamp()));
        putIfPresent(headers, MqHeaders.KEY, metadata.getKey());
        putIfPresent(headers, MqHeaders.TRACE_ID, metadata.getTraceId());
        putIfPresent(headers, MqHeaders.SPAN_ID, metadata.getSpanId());
        putIfPresent(headers, MqHeaders.PARENT_SPAN_ID, metadata.getParentSpanId());

        return MqOutboundRecord.builder()
                .topic(metadata.getTopic())
                .key(metadata.getKey())
                .body(body)
                .headers(headers)
                .deliveryMode(metadata.getDeliveryMode())
                .build();
    }

    @Override
    public <T> MqMessage<T> decode(MqInboundRecord record, Class<T> payloadType) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        if (payloadType == null) {
            throw new IllegalArgumentException("payloadType must not be null");
        }

        // 1. 拆分 headers：framework 保留头与业务自定义头。
        Map<String, String> rawHeaders = record.safeHeaders();
        Map<String, String> businessHeaders = new LinkedHashMap<>();
        Map<String, String> frameworkHeaders = Collections.emptyMap();
        if (!rawHeaders.isEmpty()) {
            frameworkHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : rawHeaders.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                if (MqHeaders.isReserved(entry.getKey())) {
                    frameworkHeaders.put(entry.getKey(), entry.getValue());
                } else {
                    businessHeaders.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // 2. 还原 metadata；缺失字段据 broker 原生信息合成。
        boolean synthetic = frameworkHeaders.isEmpty();
        MqMetadata metadata = MqMetadata.builder()
                .messageId(resolveMessageId(frameworkHeaders, record))
                .topic(record.getTopic())
                .key(resolveKey(frameworkHeaders, record))
                .timestamp(resolveTimestamp(frameworkHeaders, record))
                .traceId(frameworkHeaders.get(MqHeaders.TRACE_ID))
                .spanId(frameworkHeaders.get(MqHeaders.SPAN_ID))
                .parentSpanId(frameworkHeaders.get(MqHeaders.PARENT_SPAN_ID))
                .headers(businessHeaders)
                .source(record.getDriver())
                .nativeMessageId(record.getNativeMessageId())
                .synthetic(synthetic)
                .build();

        // 3. 反序列化 payload。
        T payload = payloadCodec.decode(record.getBody(), payloadType);

        return new MqMessage<>(payload, metadata);
    }

    /**
     * 解析消息 ID。
     * <ul>
     *     <li>优先使用 framework header 中的 messageId；</li>
     *     <li>缺失时使用 broker 原生 ID；</li>
     *     <li>都没有时生成 UUID（理论上不会出现，留作保险）。</li>
     * </ul>
     */
    private String resolveMessageId(Map<String, String> frameworkHeaders, MqInboundRecord record) {
        String headerId = frameworkHeaders.get(MqHeaders.MESSAGE_ID);
        if (headerId != null && !headerId.isBlank()) {
            return headerId;
        }
        if (record.getNativeMessageId() != null && !record.getNativeMessageId().isBlank()) {
            return record.getNativeMessageId();
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 解析业务消息键。
     * <ul>
     *     <li>优先使用 framework header；</li>
     *     <li>缺失时使用 broker 原生 key（例如 Kafka {@code ConsumerRecord#key()}）。</li>
     * </ul>
     */
    private String resolveKey(Map<String, String> frameworkHeaders, MqInboundRecord record) {
        String headerKey = frameworkHeaders.get(MqHeaders.KEY);
        if (headerKey != null) {
            return headerKey;
        }
        return record.getKey();
    }

    /**
     * 解析消息时间戳。
     * <ul>
     *     <li>优先使用 framework header；</li>
     *     <li>缺失时使用 broker 原生 timestamp；</li>
     *     <li>解析失败回落到 broker timestamp。</li>
     * </ul>
     */
    private long resolveTimestamp(Map<String, String> frameworkHeaders, MqInboundRecord record) {
        String headerTs = frameworkHeaders.get(MqHeaders.TIMESTAMP);
        if (headerTs != null && !headerTs.isBlank()) {
            try {
                return Long.parseLong(headerTs.trim());
            } catch (NumberFormatException e) {
                log.debug("MQ framework header {} 解析失败: value={}", MqHeaders.TIMESTAMP, headerTs);
            }
        }
        return record.getTimestamp();
    }

    /**
     * 仅当 value 非空时写入 map，避免序列化空字符串。
     */
    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
