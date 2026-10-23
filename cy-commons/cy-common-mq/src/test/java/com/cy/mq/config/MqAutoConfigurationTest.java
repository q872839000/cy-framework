package com.cy.mq.config;

import com.cy.mq.annotation.MqListener;
import com.cy.mq.core.MqDriverRegistry;
import com.cy.mq.core.MqDriverType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MqAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MqAutoConfiguration.class));

    @Test
    void shouldRegisterLocalDriverButRequireExplicitDefaultDriverConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(MqDriverRegistry.class);
            assertThat(context).hasBean("mqLocalExecutor");

            MqDriverRegistry registry = context.getBean(MqDriverRegistry.class);
            assertThat(registry.hasDriver(MqDriverType.LOCAL)).isTrue();
            assertThatThrownBy(() -> registry.getDriver((MqDriverType) null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未配置默认 MQ 驱动")
                    .hasMessageContaining("cy.mq.driver");
        });
    }

    @Test
    void shouldFailFastWhenListenerUsesDefaultDriverButDefaultDriverIsMissing() {
        contextRunner
                .withUserConfiguration(DefaultDriverListenerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("未显式指定 MQ 驱动")
                            .hasMessageContaining("cy.mq.driver");
                });
    }

    @Test
    void shouldBindConfiguredLocalExecutorProperties() {
        contextRunner
                .withPropertyValues(
                        "cy.mq.executors.local.core-pool-size=3",
                        "cy.mq.executors.local.max-pool-size=5",
                        "cy.mq.executors.local.queue-capacity=12",
                        "cy.mq.executors.local.keep-alive=45s",
                        "cy.mq.executors.local.rejection-policy=CALLER_RUNS"
                )
                .run(context -> {
                    ThreadPoolTaskExecutor executor = context.getBean("mqLocalExecutor", ThreadPoolTaskExecutor.class);

                    assertThat(executor.getCorePoolSize()).isEqualTo(3);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(5);
                    assertThat(executor.getKeepAliveSeconds()).isEqualTo(45);
                    assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(12);
                    assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                            .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class DefaultDriverListenerConfiguration {

        @Bean
        DefaultDriverListenerHolder defaultDriverListenerHolder() {
            return new DefaultDriverListenerHolder();
        }
    }

    static class DefaultDriverListenerHolder {

        @MqListener(topic = "demo.topic")
        public void onMessage(String payload) {
        }
    }
}
