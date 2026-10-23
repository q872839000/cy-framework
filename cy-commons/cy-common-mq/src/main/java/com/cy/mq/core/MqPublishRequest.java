package com.cy.mq.core;

import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MQ 发布请求。
 * <p>
 * 统一封装一次消息发布所需的参数，便于上层组件按需声明驱动、主题、消息键、
 * 投递模式与业务自定义头部。
 * </p>
 *
 * @author pcy
 */
@Data
@Builder
public class MqPublishRequest {

    /**
     * 指定驱动；为空时使用全局默认驱动。
     */
    private MqDriverType driver;

    /**
     * 主题名称。
     */
    private String topic;

    /**
     * 消息键。
     */
    private String key;

    /**
     * 消息体对象。
     * <p>
     * 支持 POJO、{@link Map}、{@link String}、{@code byte[]} 等任意类型；
     * 由 {@link com.cy.mq.codec.MqPayloadCodec} 序列化。
     * </p>
     */
    private Object payload;

    /**
     * 投递模式。
     */
    @Builder.Default
    private MqDeliveryMode deliveryMode = MqDeliveryMode.DEFAULT;

    /**
     * 业务自定义 headers。
     * <p>
     * 框架保留头部（{@link MqHeaders#PREFIX} 前缀）会被忽略，避免污染 framework metadata。
     * 通过 broker 的原生头部通道（Kafka headers / Redis Stream 字段）传递；
     * Redis Pub/Sub 受协议限制不传输头部。
     * </p>
     */
    @Builder.Default
    private Map<String, String> headers = new LinkedHashMap<>();
}
