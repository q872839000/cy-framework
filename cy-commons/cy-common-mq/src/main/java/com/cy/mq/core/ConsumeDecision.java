package com.cy.mq.core;

/**
 * 消费决策。
 * <p>
 * 由消费 runtime 在消费一条 {@link MqInboundRecord} 后产出，
 * 描述本次消费的最终状态与（可选的）异常信息。driver 据此决定
 * 是否提交位点 / ACK / 重投 / 投递死信。
 * </p>
 *
 * @param status 消费状态
 * @param error  异常信息（可为 {@code null}）
 * @author pcy
 */
public record ConsumeDecision(ConsumeStatus status, Throwable error) {

    /**
     * 构造消费决策。
     *
     * @param status 消费状态，不可为空
     * @param error  异常信息，可为空
     */
    public ConsumeDecision {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }

    /**
     * 业务消费成功。
     *
     * @return SUCCESS 决策
     */
    public static ConsumeDecision success() {
        return new ConsumeDecision(ConsumeStatus.SUCCESS, null);
    }

    /**
     * 标记为坏消息（解析失败、协议不匹配等不可恢复的入站错误）。
     *
     * @param error 异常信息
     * @return BAD_MESSAGE 决策
     */
    public static ConsumeDecision badMessage(Throwable error) {
        return new ConsumeDecision(ConsumeStatus.BAD_MESSAGE, error);
    }

    /**
     * 标记为可重试的业务失败。
     *
     * @param error 异常信息
     * @return RETRY 决策
     */
    public static ConsumeDecision retry(Throwable error) {
        return new ConsumeDecision(ConsumeStatus.RETRY, error);
    }

    /**
     * 标记为不可重试的业务失败。
     *
     * @param error 异常信息
     * @return DROP 决策
     */
    public static ConsumeDecision drop(Throwable error) {
        return new ConsumeDecision(ConsumeStatus.DROP, error);
    }

    /**
     * 是否需要 driver 提交 / ACK 该消息。
     * <p>
     * SUCCESS / BAD_MESSAGE / DROP 都需要 ACK；只有 RETRY 不 ACK。
     * </p>
     *
     * @return 是否需要 ACK
     */
    public boolean shouldAck() {
        return status != ConsumeStatus.RETRY;
    }
}
