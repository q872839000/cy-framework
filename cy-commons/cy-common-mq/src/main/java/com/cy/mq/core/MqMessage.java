package com.cy.mq.core;

/**
 * MQ 业务消息。
 * <p>
 * 由消费 runtime 在 decode 完成后产出并交付给业务监听器，承载
 * 业务 payload 与对应的 {@link MqMetadata}。
 * </p>
 *
 * @param payload  业务消息体
 * @param metadata 消息元数据，<b>始终非空</b>。即使是外部系统消息，
 *                 metadata 也由 runtime 据 broker 原生信息合成。
 * @param <T>      payload 类型
 * @author pcy
 */
public record MqMessage<T>(T payload, MqMetadata metadata) {

    /**
     * 构造业务消息。
     *
     * @param payload  业务 payload
     * @param metadata 元数据，必填
     */
    public MqMessage {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata must not be null");
        }
    }

    /**
     * 该消息是否来自外部系统（即 metadata 由 runtime 合成）。
     *
     * @return 是否合成
     */
    public boolean external() {
        return metadata.isSynthetic();
    }
}
