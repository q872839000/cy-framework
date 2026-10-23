package com.cy.mq.validation;

import com.cy.mq.annotation.MqListener;
import com.cy.mq.annotation.MqPublish;
import com.cy.mq.config.MqProperties;
import com.cy.mq.core.MqDriver;
import com.cy.mq.core.MqDriverRegistry;
import com.cy.mq.core.MqDriverType;
import com.cy.mq.core.MqOutboundRecord;
import com.cy.mq.core.MqRecordHandler;
import com.cy.mq.core.MqSubscribeSpec;
import com.cy.mq.core.MqSubscription;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MqDriverReferenceValidatorTest {

    @Test
    void shouldFailFastWhenListenerReferencesMissingDriver() {
        MqProperties properties = new MqProperties();
        properties.setDriver(MqDriverType.LOCAL);

        MqDriverRegistry registry = new MqDriverRegistry(properties);
        registry.registerDrivers(java.util.List.of(new StubLocalDriver()));

        MqDriverReferenceValidator validator = new MqDriverReferenceValidator(properties, registry);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("redisListenerHolder", RedisListenerHolder.class);
        context.refresh();
        validator.setApplicationContext(context);

        try {
            assertThatThrownBy(validator::afterSingletonsInstantiated)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MqListener 显式指定的 MQ 驱动未注册")
                    .hasMessageContaining("REDIS");
        } finally {
            context.close();
        }
    }

    @Test
    void shouldFailFastWhenPublisherReferencesMissingDriver() {
        MqProperties properties = new MqProperties();
        properties.setDriver(MqDriverType.LOCAL);

        MqDriverRegistry registry = new MqDriverRegistry(properties);
        registry.registerDrivers(java.util.List.of(new StubLocalDriver()));

        MqDriverReferenceValidator validator = new MqDriverReferenceValidator(properties, registry);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("kafkaPublisherHolder", KafkaPublisherHolder.class);
        context.refresh();
        validator.setApplicationContext(context);

        try {
            assertThatThrownBy(validator::afterSingletonsInstantiated)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MqPublish 显式指定的 MQ 驱动未注册")
                    .hasMessageContaining("KAFKA");
        } finally {
            context.close();
        }
    }

    @Test
    void shouldAllowStartupWhenValidationDisabled() {
        MqProperties properties = new MqProperties();
        properties.setDriver(MqDriverType.LOCAL);
        properties.getValidation().setFailFastOnMissingDriver(false);

        MqDriverRegistry registry = new MqDriverRegistry(properties);
        registry.registerDrivers(java.util.List.of(new StubLocalDriver()));

        MqDriverReferenceValidator validator = new MqDriverReferenceValidator(properties, registry);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("redisListenerHolder", RedisListenerHolder.class);
        context.registerBean("kafkaPublisherHolder", KafkaPublisherHolder.class);
        context.refresh();
        validator.setApplicationContext(context);

        try {
            assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
        } finally {
            context.close();
        }
    }

    @Test
    void shouldFailFastWhenListenerUsesDefaultDriverButNoDefaultDriverConfigured() {
        MqProperties properties = new MqProperties();

        MqDriverRegistry registry = new MqDriverRegistry(properties);
        registry.registerDrivers(java.util.List.of(new StubLocalDriver()));

        MqDriverReferenceValidator validator = new MqDriverReferenceValidator(properties, registry);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("defaultDriverListenerHolder", DefaultDriverListenerHolder.class);
        context.refresh();
        validator.setApplicationContext(context);

        try {
            assertThatThrownBy(validator::afterSingletonsInstantiated)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MqListener 未显式指定 MQ 驱动")
                    .hasMessageContaining("cy.mq.driver");
        } finally {
            context.close();
        }
    }

    @Test
    void shouldFailFastWhenPublisherUsesDefaultDriverButNoDefaultDriverConfigured() {
        MqProperties properties = new MqProperties();

        MqDriverRegistry registry = new MqDriverRegistry(properties);
        registry.registerDrivers(java.util.List.of(new StubLocalDriver()));

        MqDriverReferenceValidator validator = new MqDriverReferenceValidator(properties, registry);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("defaultDriverPublisherHolder", DefaultDriverPublisherHolder.class);
        context.refresh();
        validator.setApplicationContext(context);

        try {
            assertThatThrownBy(validator::afterSingletonsInstantiated)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MqPublish 未显式指定 MQ 驱动")
                    .hasMessageContaining("cy.mq.driver");
        } finally {
            context.close();
        }
    }

    static class RedisListenerHolder {

        @MqListener(topic = "demo.topic", driver = "REDIS")
        public void onMessage(String payload) {
        }
    }

    static class KafkaPublisherHolder {

        @MqPublish(topic = "demo.topic", driver = "KAFKA")
        public String publish() {
            return "ok";
        }
    }

    static class DefaultDriverListenerHolder {

        @MqListener(topic = "demo.topic")
        public void onMessage(String payload) {
        }
    }

    static class DefaultDriverPublisherHolder {

        @MqPublish(topic = "demo.topic")
        public String publish() {
            return "ok";
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
}
