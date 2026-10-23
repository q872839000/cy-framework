package com.cy.mq.core;

import com.cy.mq.runtime.MqConsumerRuntime;
import com.cy.mq.runtime.MqProducerRuntime;
import lombok.RequiredArgsConstructor;

import java.util.function.Consumer;

/**
 * 默认 MQ 模板实现。
 * <p>
 * 把发布、订阅、主题管理委托给对应运行时与 driver；
 * 自身只做参数封装与路由。
 * </p>
 *
 * @author pcy
 */
@RequiredArgsConstructor
public class DefaultMqTemplate implements MqTemplate {

    /**
     * 驱动注册中心。
     */
    private final MqDriverRegistry driverRegistry;

    /**
     * 发布运行时。
     */
    private final MqProducerRuntime producerRuntime;

    /**
     * 消费运行时。
     */
    private final MqConsumerRuntime consumerRuntime;

    @Override
    public void publish(MqPublishRequest request) {
        producerRuntime.publish(request);
    }

    @Override
    public void createTopics(MqDriverType driver, Iterable<MqTopicSpec> topics) {
        if (topics == null) {
            return;
        }
        MqDriver mqDriver = driverRegistry.getDriver(driver);
        mqDriver.createTopics(topics);
    }

    @Override
    public <T> MqSubscription subscribe(String topic, Class<T> payloadType, Consumer<T> consumer) {
        return subscribe(null, topic, null, payloadType, consumer);
    }

    @Override
    public <T> MqSubscription subscribe(MqDriverType driver, String topic, String groupId,
                                        Class<T> payloadType, Consumer<T> consumer) {
        // 兼容仅关心 payload 的回调：包装为标准 MqConsumer 后委托。
        return subscribe(driver, topic, groupId, payloadType,
                (payload, metadata) -> consumer.accept(payload));
    }

    @Override
    public <T> MqSubscription subscribe(MqDriverType driver, String topic, String groupId,
                                        Class<T> payloadType, MqConsumer<T> consumer) {
        MqDriver mqDriver = driverRegistry.getDriver(driver);
        MqRecordHandler handler = consumerRuntime.newSingleHandler(payloadType, consumer);
        MqSubscribeSpec spec = MqSubscribeSpec.builder()
                .topic(topic)
                .groupId(groupId)
                .batch(false)
                .build();
        return mqDriver.subscribe(spec, handler);
    }

    @Override
    public <T> MqSubscription subscribeBatch(MqDriverType driver, String topic, String groupId,
                                             Class<T> payloadType, MqBatchConsumer<T> consumer) {
        MqDriver mqDriver = driverRegistry.getDriver(driver);
        MqRecordHandler handler = consumerRuntime.newBatchHandler(payloadType, consumer);
        MqSubscribeSpec spec = MqSubscribeSpec.builder()
                .topic(topic)
                .groupId(groupId)
                .batch(true)
                .build();
        return mqDriver.subscribe(spec, handler);
    }
}
