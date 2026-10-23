package com.cy.mq.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MQ 订阅注解。
 * <p>
 * 标记在 Spring Bean 的方法上，组件启动时会自动扫描并注册订阅。运行时由
 * {@link com.cy.mq.runtime.MqConsumerRuntime} 统一处理 decode、链路追踪与异常映射；
 * 业务方法只关心 payload 与（可选的）{@link com.cy.mq.core.MqMetadata}。
 * </p>
 * <p>
 * 支持的方法签名：
 * </p>
 * <ul>
 *     <li>单条：<code>void onMessage(T payload)</code></li>
 *     <li>单条：<code>void onMessage(T payload, com.cy.mq.core.MqMetadata metadata)</code></li>
 *     <li>批量：<code>void onMessages(java.util.List&lt;T&gt; payloads)</code></li>
 *     <li>批量：<code>void onMessages(java.util.List&lt;T&gt; payloads,
 *     java.util.List&lt;com.cy.mq.core.MqMetadata&gt; metadataList)</code></li>
 * </ul>
 * <p>
 * payload 类型 <code>T</code> 可以是任意类型：POJO、{@code byte[]}、{@code String}、
 * {@code com.alibaba.fastjson2.JSONObject}、{@code Map}、{@code Object} 等；
 * 由 {@link com.cy.mq.codec.MqPayloadCodec} 反序列化。
 * </p>
 * <p>
 * 异常处理：
 * </p>
 * <ul>
 *     <li>抛出 {@link com.cy.mq.exception.MqConsumeException}（{@code retryable=false}）：
 *     不可重试，driver 立即提交位点 / ACK 并按需投递死信。</li>
 *     <li>抛出其他异常：可重试，driver 按各自策略保留消息（Kafka 内置重试 + DLQ；
 *     Redis Stream 不 ACK 等待 pending reclaim；Pub/Sub 仅记录失败）。</li>
 *     <li>正常返回：消费成功，driver 提交位点 / ACK。</li>
 * </ul>
 *
 * @author pcy
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MqListener {

    /**
     * Topic
     */
    String topic();

    /**
     * 驱动类型
     */
    String driver() default "";

    /**
     * 消费组（Kafka 等驱动使用）
     */
    String groupId() default "";

    /**
     * 消息体类型
     * <p>
     * 默认从方法参数推断。
     * </p>
     */
    Class<?> payloadType() default Void.class;

    /**
     * 是否启用批量消费模式。
     */
    boolean batch() default false;
}
