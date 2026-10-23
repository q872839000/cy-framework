package com.cy.mq.core;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import java.util.Collections;
import java.util.Map;

/**
 * 出站原始记录。
 * <p>
 * 由 codec 在发布前生成，作为 driver 写入底层 broker 的统一格式。
 * driver 接收到 {@link MqOutboundRecord} 后只负责把它写入 broker，
 * <b>不</b>再做 payload 序列化或 metadata 拼装。
 * </p>
 *
 * @author pcy
 */
@Getter
@Builder(toBuilder = true)
@RequiredArgsConstructor
@ToString
public class MqOutboundRecord {

    /**
     * 目标 driver。
     * <p>由 template 据 {@link MqPublishRequest} 路由后填入。</p>
     */
    private final MqDriverType driver;

    /**
     * 主题。
     */
    private final String topic;

    /**
     * 业务消息键。
     * <p>Kafka 用于 partition 路由；其他 driver 仅作为业务标识。可为 {@code null}。</p>
     */
    private final String key;

    /**
     * 业务 payload 的原始字节。
     * <p>由 {@link com.cy.mq.codec.MqPayloadCodec} 序列化产出。</p>
     */
    private final byte[] body;

    /**
     * 头部集合。
     * <p>
     * 包含 framework 保留头（{@link MqHeaders#PREFIX} 前缀，由 codec 写入）与
     * 业务自定义头部（由发布方写入）。
     * </p>
     */
    private final Map<String, String> headers;

    /**
     * 投递模式。
     * <p>仅 Redis driver 用于决定 Stream / Pub/Sub / 双写。其他 driver 可忽略。</p>
     */
    private final MqDeliveryMode deliveryMode;

    /**
     * 安全获取 headers。
     *
     * @return headers（永远非空）
     */
    public Map<String, String> safeHeaders() {
        return headers == null ? Collections.emptyMap() : headers;
    }
}
