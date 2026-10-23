package com.cy.mq.listener;

import com.cy.mq.annotation.MqListener;
import com.cy.mq.config.MqProperties;
import com.cy.mq.core.MqBatchConsumer;
import com.cy.mq.core.MqConsumer;
import com.cy.mq.core.MqDriver;
import com.cy.mq.core.MqDriverRegistry;
import com.cy.mq.core.MqDriverType;
import com.cy.mq.core.MqOutboundRecord;
import com.cy.mq.core.MqRecordHandler;
import com.cy.mq.core.MqSubscribeSpec;
import com.cy.mq.core.MqSubscription;
import com.cy.mq.core.MqTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MqListenerRegistrar} 单元测试。
 */
class MqListenerRegistrarTest {

    @Test
    void shouldFailFastWhenListenerRegistrationFails() {
        RecordingMqTemplate mqTemplate = new RecordingMqTemplate();
        MqProperties properties = new MqProperties();
        properties.setDriver(MqDriverType.LOCAL);

        MqDriverRegistry registry = new MqDriverRegistry(properties);
        registry.registerDrivers(List.of(new StubLocalDriver()));

        MqListenerRegistrar registrar = new MqListenerRegistrar(mqTemplate, properties, registry);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("brokenListener", BrokenListener.class);
        context.registerBean("healthyListener", HealthyListener.class);
        context.refresh();
        registrar.setApplicationContext(context);

        try {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, registrar::start);

            assertThat(registrar.isRunning()).isFalse();
            assertThat(mqTemplate.attemptedTopics).containsExactlyInAnyOrder("broken.topic", "healthy.topic");
            assertThat(mqTemplate.registeredTopics).containsExactly("healthy.topic");
        } finally {
            registrar.stop();
            context.close();
        }
    }

    @Test
    void shouldRegisterExplicitDriverListenerWhenDefaultDriverDiffers() {
        RecordingMqTemplate mqTemplate = new RecordingMqTemplate();
        MqProperties properties = new MqProperties();
        properties.setDriver(MqDriverType.LOCAL);

        MqDriverRegistry registry = new MqDriverRegistry(properties);
        registry.registerDrivers(List.of(new StubLocalDriver(), new StubRedisDriver()));

        MqListenerRegistrar registrar = new MqListenerRegistrar(mqTemplate, properties, registry);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("defaultDriverListener", DefaultDriverListener.class);
        context.registerBean("redisListener", RedisDriverListener.class);
        context.refresh();
        registrar.setApplicationContext(context);

        try {
            registrar.start();

            assertThat(mqTemplate.registeredTopics).containsExactlyInAnyOrder("default.topic", "redis.topic");
            assertThat(mqTemplate.registeredSubscriptions)
                    .containsExactlyInAnyOrder("LOCAL:default.topic", "REDIS:redis.topic");
        } finally {
            registrar.stop();
            context.close();
        }
    }

    @Test
    void shouldResolveSpelTopicFromBeanFactory() {
        RecordingMqTemplate mqTemplate = new RecordingMqTemplate();
        MqProperties properties = new MqProperties();
        properties.setDriver(MqDriverType.LOCAL);

        MqDriverRegistry registry = new MqDriverRegistry(properties);
        registry.registerDrivers(List.of(new StubLocalDriver()));

        MqListenerRegistrar registrar = new MqListenerRegistrar(mqTemplate, properties, registry);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("topicProvider", TopicProvider.class);
        context.registerBean("spelListener", SpelListener.class);
        context.refresh();
        registrar.setApplicationContext(context);

        try {
            registrar.start();
            assertThat(mqTemplate.registeredTopics).contains("spel.topic");
        } finally {
            registrar.stop();
            context.close();
        }
    }

    static class BrokenListener {
        @MqListener(topic = "broken.topic")
        public void onMessage(String payload) {
        }
    }

    static class HealthyListener {
        @MqListener(topic = "healthy.topic")
        public void onMessage(String payload) {
        }
    }

    static class DefaultDriverListener {
        @MqListener(topic = "default.topic")
        public void onMessage(String payload) {
        }
    }

    static class RedisDriverListener {
        @MqListener(topic = "redis.topic", driver = "REDIS")
        public void onMessage(String payload) {
        }
    }

    static class TopicProvider {
        public String topic() {
            return "spel.topic";
        }
    }

    static class SpelListener {
        @MqListener(topic = "#{@topicProvider.topic()}")
        public void onMessage(String payload) {
        }
    }

    /**
     * 记录调用以便断言：模拟 {@link MqTemplate}，对 {@code broken.topic} 抛异常以验证失败传播。
     */
    static class RecordingMqTemplate implements MqTemplate {

        private final List<String> attemptedTopics = new ArrayList<>();
        private final List<String> registeredTopics = new ArrayList<>();
        private final List<String> registeredSubscriptions = new ArrayList<>();

        @Override
        public void publish(com.cy.mq.core.MqPublishRequest request) {
        }

        @Override
        public void createTopics(MqDriverType driver, Iterable<com.cy.mq.core.MqTopicSpec> topics) {
        }

        @Override
        public <T> MqSubscription subscribe(String topic, Class<T> payloadType, Consumer<T> consumer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> MqSubscription subscribe(MqDriverType driver,
                                            String topic,
                                            String groupId,
                                            Class<T> payloadType,
                                            Consumer<T> consumer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> MqSubscription subscribe(MqDriverType driver,
                                            String topic,
                                            String groupId,
                                            Class<T> payloadType,
                                            MqConsumer<T> consumer) {
            attemptedTopics.add(topic);
            if ("broken.topic".equals(topic)) {
                throw new IllegalStateException("mock subscribe failure");
            }
            registeredTopics.add(topic);
            registeredSubscriptions.add(driver + ":" + topic);
            return () -> {
            };
        }

        @Override
        public <T> MqSubscription subscribeBatch(MqDriverType driver,
                                                 String topic,
                                                 String groupId,
                                                 Class<T> payloadType,
                                                 MqBatchConsumer<T> consumer) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * 桩 driver：所有方法均为 no-op，仅满足 driver registry 的引用校验。
     */
    static class StubLocalDriver implements MqDriver {

        @Override
        public MqDriverType type() {
            return MqDriverType.LOCAL;
        }

        @Override
        public void publish(MqOutboundRecord record) {
        }

        @Override
        public MqSubscription subscribe(MqSubscribeSpec spec, MqRecordHandler handler) {
            return () -> {
            };
        }
    }

    static class StubRedisDriver implements MqDriver {

        @Override
        public MqDriverType type() {
            return MqDriverType.REDIS;
        }

        @Override
        public void publish(MqOutboundRecord record) {
        }

        @Override
        public MqSubscription subscribe(MqSubscribeSpec spec, MqRecordHandler handler) {
            return () -> {
            };
        }
    }
}
