package com.cy.mq.health;

import com.cy.mq.config.MqAutoConfiguration;
import com.cy.mq.core.MqDriverRegistry;
import com.cy.mq.core.MqDriverType;
import com.cy.mq.metrics.MqMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MqHealthIndicator 单元测试。
 *
 * @author pcy
 * @date 2026/04/16
 */
class MqHealthIndicatorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MqAutoConfiguration.class,
                    com.cy.mq.config.MqHealthAutoConfiguration.class))
            .withPropertyValues("cy.mq.driver=LOCAL");

    @BeforeEach
    void setUp() {
        // 重置指标
        MqMetrics.reset();
    }

    @Test
    void shouldReportUpWhenDriversRegisteredAndMetricsHealthy() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(MqHealthIndicator.class);

            MqHealthIndicator healthIndicator = context.getBean(MqHealthIndicator.class);
            Health health = healthIndicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsKey("registeredDrivers");
            assertThat(health.getDetails()).containsKey("driverCount");
            assertThat(health.getDetails().get("driverCount")).isEqualTo(1);
        });
    }

    @Test
    void shouldReportDownWhenPublishFailureRateExceedsThreshold() {
        contextRunner.run(context -> {
            MqHealthIndicator healthIndicator = context.getBean(MqHealthIndicator.class);

            // 模拟高发布失败率（超过 10% 阈值）
            for (int i = 0; i < 100; i++) {
                MqMetrics.recordPublishSuccess("test-topic", "LOCAL", "msg-" + i);
            }
            for (int i = 0; i < 20; i++) {
                MqMetrics.recordPublishFailure("test-topic", "LOCAL", "msg-fail-" + i);
            }

            Health health = healthIndicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsKey("reason");
            assertThat(health.getDetails().get("reason").toString())
                    .contains("发布失败率过高");
        });
    }

    @Test
    void shouldReportDownWhenConsumeFailureRateExceedsThreshold() {
        contextRunner.run(context -> {
            MqHealthIndicator healthIndicator = context.getBean(MqHealthIndicator.class);

            // 模拟高消费失败率（超过 5% 阈值）
            for (int i = 0; i < 100; i++) {
                MqMetrics.recordConsumeSuccess("test-topic", "LOCAL", "test-group", "msg-" + i);
            }
            for (int i = 0; i < 10; i++) {
                MqMetrics.recordConsumeFailure("test-topic", "LOCAL", "test-group", "msg-fail-" + i);
            }

            Health health = healthIndicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsKey("reason");
            assertThat(health.getDetails().get("reason").toString())
                    .contains("消费失败率过高");
        });
    }

    @Test
    void shouldIncludeMetricsInHealthDetails() {
        contextRunner.run(context -> {
            MqHealthIndicator healthIndicator = context.getBean(MqHealthIndicator.class);

            // 记录一些指标
            MqMetrics.recordPublishSuccess("test-topic", "LOCAL", "msg-1");
            MqMetrics.recordPublishSuccess("test-topic", "LOCAL", "msg-2");
            MqMetrics.recordConsumeSuccess("test-topic", "LOCAL", "test-group", "msg-1");

            Health health = healthIndicator.health();

            assertThat(health.getDetails()).containsKey("publishSuccessCount");
            assertThat(health.getDetails()).containsKey("publishFailureCount");
            assertThat(health.getDetails()).containsKey("publishFailureRate");
            assertThat(health.getDetails()).containsKey("consumeSuccessCount");
            assertThat(health.getDetails()).containsKey("consumeFailureCount");
            assertThat(health.getDetails()).containsKey("consumeFailureRate");
        });
    }
}
