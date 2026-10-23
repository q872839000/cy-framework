package com.cy.mq.core;

/**
 * 消费结果状态枚举。
 * <p>
 * 表示消费 runtime 处理一条入站消息后给出的最终结论；
 * driver 据此决定是否提交位点 / ACK / 重投。
 * </p>
 *
 * @author pcy
 */
public enum ConsumeStatus {

    /**
     * 业务消费成功。
     * <ul>
     *     <li>Kafka：commit 该 record 的 offset。</li>
     *     <li>Redis Stream：XACK 该 entry。</li>
     *     <li>Redis Pub/Sub：仅记录 metrics，无 ACK 概念。</li>
     *     <li>Local：完成投递。</li>
     * </ul>
     */
    SUCCESS,

    /**
     * 消息无法被解析或不符合监听契约（坏消息）。
     * <p>driver 应直接 ACK / commit，避免阻塞后续消息。这种状态用于
     * 不可恢复的协议错误，例如 body 反序列化失败、payload 类型不匹配等。</p>
     */
    BAD_MESSAGE,

    /**
     * 业务消费失败，但允许重试。
     * <ul>
     *     <li>Kafka：driver 按重试策略处理（指数退避，最终可能进入 DLQ）。</li>
     *     <li>Redis Stream：driver 不 ACK，消息保留在 PEL，等待 pending reclaim 重新投递。</li>
     *     <li>Redis Pub/Sub：仅记录失败，无法重投。</li>
     *     <li>Local：driver 按配置决定（默认丢弃并记录）。</li>
     * </ul>
     */
    RETRY,

    /**
     * 业务消费失败且不可重试。
     * <p>driver 应立即 ACK / commit 并按需投递到死信队列，避免无限重试。</p>
     */
    DROP
}
