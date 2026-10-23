package com.cy.mq.exception;

import java.io.Serial;

/**
 * MQ 发布异常。
 * <p>
 * 当消息发布失败时抛出，包含主题、消息 ID 等上下文信息。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
public class MqPublishException extends MqException {

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

    public MqPublishException(String topic, String messageId, String message) {
        super(String.format("MQ 发布失败: topic=%s, messageId=%s, reason=%s", topic, messageId, message));
        this.topic = topic;
        this.messageId = messageId;
    }

    public MqPublishException(String topic, String messageId, String message, Throwable cause) {
        super(String.format("MQ 发布失败: topic=%s, messageId=%s, reason=%s", topic, messageId, message), cause);
        this.topic = topic;
        this.messageId = messageId;
    }

    public MqPublishException(String topic, String messageId, Throwable cause) {
        super(String.format("MQ 发布失败: topic=%s, messageId=%s", topic, messageId), cause);
        this.topic = topic;
        this.messageId = messageId;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageId() {
        return messageId;
    }
}
