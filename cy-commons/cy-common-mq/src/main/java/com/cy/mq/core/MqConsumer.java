package com.cy.mq.core;

/**
 * 单条消息消费函数。
 * <p>
 * 用于编程式订阅与 {@link com.cy.mq.annotation.MqListener} 注解监听器的统一回调约定。
 * 接收业务 payload 与对应的 {@link MqMetadata}（可能由消费 runtime 据外部消息合成）。
 * </p>
 * <p>
 * 实现要点：
 * </p>
 * <ul>
 *     <li>抛出 {@link com.cy.mq.exception.MqConsumeException}（{@code retryable=false}）表示<b>不可重试</b>，
 *     driver 会立即 ACK 并按需投递死信。</li>
 *     <li>抛出其他任意异常表示<b>可重试</b>，driver 会按重试策略保留消息。</li>
 *     <li>正常返回视为消费成功，driver 会 ACK / commit 位点。</li>
 * </ul>
 *
 * @param <T> payload 类型
 * @author pcy
 */
@FunctionalInterface
public interface MqConsumer<T> {

    /**
     * 消费回调。
     *
     * @param payload  业务消息体（可能为 {@code null}）
     * @param metadata 消息元数据，永远非 {@code null}
     * @throws Exception 业务异常；framework 据异常类型决定 ACK / 重试 / 死信
     */
    void accept(T payload, MqMetadata metadata) throws Exception;
}
