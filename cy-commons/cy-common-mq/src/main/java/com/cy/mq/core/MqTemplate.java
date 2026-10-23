package com.cy.mq.core;

import java.util.List;
import java.util.function.Consumer;

/**
 * MQ 模板。
 * <p>
 * 提供统一的消息发布与订阅能力。
 * </p>
 *
 * @author pcy
 */
public interface MqTemplate {

    /**
     * 发布消息。
     *
     * @param request 发布请求
     */
    void publish(MqPublishRequest request);

    /**
     * 使用默认驱动创建单个主题。
     *
     * @param topic 主题名
     */
    default void createTopic(String topic) {
        createTopics(List.of(MqTopicSpec.of(topic)));
    }

    /**
     * 使用默认驱动批量创建主题。
     *
     * @param topics 主题定义
     */
    default void createTopics(Iterable<MqTopicSpec> topics) {
        createTopics(null, topics);
    }

    /**
     * 指定驱动批量创建主题。
     *
     * @param driver 驱动类型
     * @param topics 主题定义
     */
    void createTopics(MqDriverType driver, Iterable<MqTopicSpec> topics);

    /**
     * 使用默认驱动发布消息。
     *
     * @param topic   主题
     * @param payload 消息体
     */
    default void publish(String topic, Object payload) {
        publish(MqPublishRequest.builder()
                .topic(topic)
                .payload(payload)
                .build());
    }

    /**
     * 指定驱动发布消息。
     *
     * @param driver  驱动类型
     * @param topic   主题
     * @param payload 消息体
     */
    default void publish(MqDriverType driver, String topic, Object payload) {
        publish(MqPublishRequest.builder()
                .driver(driver)
                .topic(topic)
                .payload(payload)
                .build());
    }

    /**
     * 指定驱动与消息键发布消息。
     *
     * @param driver  驱动类型
     * @param topic   主题
     * @param key     消息键
     * @param payload 消息体
     */
    default void publish(MqDriverType driver, String topic, String key, Object payload) {
        publish(MqPublishRequest.builder()
                .driver(driver)
                .topic(topic)
                .key(key)
                .payload(payload)
                .build());
    }

    /**
     * 订阅消息。
     *
     * @param topic       主题
     * @param payloadType 消息类型
     * @param consumer    消费函数
     * @param <T>         消息类型
     * @return 订阅句柄
     */
    <T> MqSubscription subscribe(String topic, Class<T> payloadType, Consumer<T> consumer);

    /**
     * 订阅消息。
     *
     * @param driver      驱动类型
     * @param topic       主题
     * @param groupId     消费组
     * @param payloadType 消息类型
     * @param consumer    消费函数
     * @param <T>         消息类型
     * @return 订阅句柄
     */
    <T> MqSubscription subscribe(MqDriverType driver, String topic, String groupId, Class<T> payloadType, Consumer<T> consumer);

    /**
     * 订阅消息，并保留消息元数据。
     *
     * @param driver      驱动类型
     * @param topic       主题
     * @param groupId     消费组
     * @param payloadType 消息类型
     * @param consumer    消费函数
     * @param <T>         消息类型
     * @return 订阅句柄
     */
    <T> MqSubscription subscribe(MqDriverType driver, String topic, String groupId, Class<T> payloadType, MqConsumer<T> consumer);

    /**
     * 批量订阅消息。
     *
     * @param driver      驱动类型
     * @param topic       主题
     * @param groupId     消费组
     * @param payloadType 消息类型
     * @param consumer    批量消费函数
     * @param <T>         消息类型
     * @return 订阅句柄
     */
    <T> MqSubscription subscribeBatch(MqDriverType driver, String topic, String groupId, Class<T> payloadType, MqBatchConsumer<T> consumer);
}
