package com.cy.mq.exception;

import java.io.Serial;

/**
 * MQ 消费异常。
 * <p>
 * 当消息消费失败时抛出，包含主题、消息 ID 等上下文信息。
 * 驱动可根据此异常决定是否重试、记录死信等。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
public class MqConsumeException extends MqException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主题名称。
     */
    private final String topic;

    /**
     * 消息 ID。
     */
    private final String messageId;

    /**
     * 是否可重试。
     * <p>
     * true: 临时性错误（如网络超时、数据库连接失败），可以重试
     * false: 永久性错误（如数据格式错误、业务规则校验失败），不应重试
     * </p>
     */
    private final boolean retryable;

    public MqConsumeException(String topic, String messageId, String message, boolean retryable) {
        super(String.format("MQ 消费失败: topic=%s, messageId=%s, retryable=%s, reason=%s",
                topic, messageId, retryable, message));
        this.topic = topic;
        this.messageId = messageId;
        this.retryable = retryable;
    }

    public MqConsumeException(String topic, String messageId, String message, Throwable cause, boolean retryable) {
        super(String.format("MQ 消费失败: topic=%s, messageId=%s, retryable=%s, reason=%s",
                topic, messageId, retryable, message), cause);
        this.topic = topic;
        this.messageId = messageId;
        this.retryable = retryable;
    }

    public MqConsumeException(String topic, String messageId, Throwable cause, boolean retryable) {
        super(String.format("MQ 消费失败: topic=%s, messageId=%s, retryable=%s",
                topic, messageId, retryable), cause);
        this.topic = topic;
        this.messageId = messageId;
        this.retryable = retryable;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageId() {
        return messageId;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
