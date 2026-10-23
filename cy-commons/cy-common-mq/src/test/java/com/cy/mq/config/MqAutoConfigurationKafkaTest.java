package com.cy.mq.config;

import com.cy.mq.core.MqDriverRegistry;
import com.cy.mq.core.MqDriverType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class MqAutoConfigurationKafkaTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MqAutoConfiguration.class, KafkaAutoConfiguration.class))
            .withPropertyValues(
                    "cy.mq.driver=KAFKA",
                    "spring.kafka.bootstrap-servers=127.0.0.1:9092",
                    "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                    "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
                    "spring.kafka.consumer.group-id=test-group",
                    "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                    "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer"
            );

    @Test
    void shouldRegisterKafkaDriverWhenKafkaAutoConfigurationIsAvailable() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(MqDriverRegistry.class);
            assertThat(context).hasBean("kafkaMqDriver");

            MqDriverRegistry registry = context.getBean(MqDriverRegistry.class);
            assertThat(registry.hasDriver(MqDriverType.KAFKA)).isTrue();
            assertThatNoException().isThrownBy(() -> registry.getDriver(MqDriverType.KAFKA));
        });
    }

    @Test
    void shouldRegisterKafkaDriverEvenWhenDefaultDriverIsLocal() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MqAutoConfiguration.class, KafkaAutoConfiguration.class))
                .withPropertyValues(
                        "cy.mq.driver=LOCAL",
                        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
                        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
                        "spring.kafka.consumer.group-id=test-group",
                        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                        "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MqDriverRegistry.class);
                    assertThat(context).hasBean("kafkaMqDriver");
                    assertThat(context).hasSingleBean(com.cy.mq.driver.local.LocalMqDriver.class);

                    MqDriverRegistry registry = context.getBean(MqDriverRegistry.class);
                    assertThat(registry.hasDriver(MqDriverType.LOCAL)).isTrue();
                    assertThat(registry.hasDriver(MqDriverType.KAFKA)).isTrue();
                    assertThatNoException().isThrownBy(() -> registry.getDriver(MqDriverType.KAFKA));
                });
    }

    @Test
    void shouldOnlyRegisterDriversIncludedInEnabledDrivers() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MqAutoConfiguration.class, KafkaAutoConfiguration.class))
                .withPropertyValues(
                        "cy.mq.driver=LOCAL",
                        "cy.mq.enabled-drivers=LOCAL",
                        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
                        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
                        "spring.kafka.consumer.group-id=test-group",
                        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                        "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MqDriverRegistry.class);
                    MqDriverRegistry registry = context.getBean(MqDriverRegistry.class);
                    assertThat(registry.hasDriver(MqDriverType.KAFKA)).isFalse();
                    assertThat(registry.hasDriver(MqDriverType.LOCAL)).isTrue();
                });
    }

    @Test
    void shouldFailFastWhenDefaultKafkaDriverIsUnavailable() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MqAutoConfiguration.class, KafkaAutoConfiguration.class))
                .withPropertyValues("cy.mq.driver=KAFKA")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(org.springframework.beans.factory.BeanCreationException.class)
                            .hasMessageContaining("当前默认 MQ 驱动 KAFKA 未完成注册");
                });
    }

    @Test
    void shouldAllowStartupWhenMissingDefaultDriverAndFailFastDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MqAutoConfiguration.class, KafkaAutoConfiguration.class))
                .withPropertyValues(
                        "cy.mq.driver=KAFKA",
                        "cy.mq.validation.fail-fast-on-missing-driver=false"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MqDriverRegistry.class);

                    MqDriverRegistry registry = context.getBean(MqDriverRegistry.class);
                    assertThat(registry.hasDriver(MqDriverType.KAFKA)).isFalse();
                });
    }
}
