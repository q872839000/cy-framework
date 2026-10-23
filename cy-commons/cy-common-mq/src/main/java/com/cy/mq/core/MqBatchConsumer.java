package com.cy.mq.core;

import java.util.List;

/**
 * 批量消息消费函数。
 * <p>
 * 用于一次性接收一批消息的回调约定。{@code payloads} 与 {@code metadataList}
 * 长度一致、按 broker 顺序排列。
 * </p>
 * <p>
 * 整体异常处理语义同 {@link MqConsumer}：
 * </p>
 * <ul>
 *     <li>抛 {@link com.cy.mq.exception.MqConsumeException}（{@code retryable=false}）：整批不可重试。</li>
 *     <li>抛其他异常：整批可重试。</li>
 *     <li>正常返回：整批成功。</li>
 * </ul>
 *
 * @param <T> payload 类型
 * @author pcy
 */
@FunctionalInterface
public interface MqBatchConsumer<T> {

    /**
     * 批量消费回调。
     *
     * @param payloads     批量业务消息体（按 broker 顺序）
     * @param metadataList 批量消息元数据（与 payloads 一一对应，永远非 {@code null}）
     * @throws Exception 业务异常；framework 据异常类型决定整批 ACK / 重试 / 死信
     */
    void accept(List<T> payloads, List<MqMetadata> metadataList) throws Exception;
}
