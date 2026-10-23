package com.cy.mq.core;

/**
 * MQ 框架保留头与字段名常量。
 * <p>
 * 本组件以 {@code __cy_mq_*} 命名空间在底层 broker（Kafka headers / Redis Stream fields 等）
 * 上传输框架元数据，与业务字段严格区分，避免与外部系统消息冲突。
 * </p>
 * <ul>
 *     <li><b>Kafka</b>：以下常量直接作为 Kafka header key 使用。</li>
 *     <li><b>Redis Stream</b>：以下常量直接作为 Stream entry 的 field 名使用，
 *     业务 payload 写入 {@link #PAYLOAD_FIELD} 字段。</li>
 *     <li><b>Redis Pub/Sub</b>：受协议限制不携带 metadata，body 即 payload。</li>
 *     <li><b>外部系统消息</b>：可能不包含任何保留字段，由消费 runtime 自动合成 metadata。</li>
 * </ul>
 *
 * @author pcy
 */
public final class MqHeaders {

    /**
     * 框架保留头/字段名前缀。
     * <p>
     * 业务方禁止使用以该前缀开头的字段，避免与框架保留字段冲突。
     * </p>
     */
    public static final String PREFIX = "__cy_mq_";

    /**
     * 消息 ID。
     */
    public static final String MESSAGE_ID = PREFIX + "id";

    /**
     * 消息发布时间戳（毫秒）。
     */
    public static final String TIMESTAMP = PREFIX + "ts";

    /**
     * 业务消息键。
     */
    public static final String KEY = PREFIX + "key";

    /**
     * 链路追踪 ID。
     */
    public static final String TRACE_ID = PREFIX + "trace_id";

    /**
     * 当前 Span ID。
     */
    public static final String SPAN_ID = PREFIX + "span_id";

    /**
     * 父 Span ID。
     */
    public static final String PARENT_SPAN_ID = PREFIX + "parent_span_id";

    /**
     * payload 类型提示（业务可选）。
     * <p>
     * 当发布端希望声明 payload 的逻辑类型时（不一定是 JVM 类名），
     * 可写入此 header；消费端可用于动态分派但不强依赖。
     * </p>
     */
    public static final String PAYLOAD_TYPE = PREFIX + "payload_type";

    /**
     * Redis Stream 中存放业务 payload 的固定字段名。
     * <p>
     * 注意：该字段不带 {@link #PREFIX}，目的是与外部系统直接写入
     * {@code XADD topic * payload xxx} 的常见约定保持一致；外部消息只要
     * 写入 {@code payload} 字段，即可被本组件按 raw payload 消费。
     * </p>
     */
    public static final String PAYLOAD_FIELD = "payload";

    private MqHeaders() {
    }

    /**
     * 判断给定 header / field 名是否为框架保留键。
     *
     * @param name 名称
     * @return 是否为框架保留键
     */
    public static boolean isReserved(String name) {
        return name != null && name.startsWith(PREFIX);
    }
}
