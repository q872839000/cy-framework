package com.cy.mq.config;

import com.cy.mq.metrics.MicrometerMqMetrics;
import com.cy.mq.metrics.MqMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MqMetricsAutoConfiguration} 防御性装配回归测试。
 * <p>
 * 验证三种关键场景下自动配置的行为，确保 {@code micrometer-core} 标 {@code optional=true}
 * 的语义被忠实兑现：装可用、不装不崩、装但缺 {@code MeterRegistry} bean 也不崩。
 * </p>
 */
class MqMetricsAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MqMetricsAutoConfiguration.class));

    /**
     * 跨用例清理 {@link MqMetrics} 全局静态记录器，避免上一个用例装配的
     * {@link MicrometerMqMetrics} 引用泄漏到下一个用例。
     */
    @AfterEach
    void resetMicrometerRecorder() {
        MqMetrics.registerMicrometerRecorder(null);
    }

    /**
     * {@code micrometer-core} 在 classpath 且容器内有 {@link MeterRegistry} bean 时，
     * 嵌套的 {@code MicrometerMqMetricsConfiguration} 应当装配 {@link MicrometerMqMetrics}。
     */
    @Test
    void shouldRegisterMicrometerMqMetricsWhenMeterRegistryBeanPresent() {
        contextRunner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MicrometerMqMetrics.class);
                });
    }

    /**
     * Regression for the {@code UnsatisfiedDependencyException} encountered when host services
     * (例如 {@code orrery-butler-service}/{@code orrery-admin-service}/{@code orrery-gateway})
     * 通过其他模块（{@code cy-common-starter-conversation} 硬依赖等）拉入了
     * {@code micrometer-core}，但宿主自己没有引入 {@code spring-boot-starter-actuator}，
     * 导致 {@link MeterRegistry} 类在 classpath 上但容器内不存在 {@link MeterRegistry} bean。
     * <p>
     * 修复前：{@code micrometerMqMetrics(MeterRegistry)} 仅有 {@code @ConditionalOnClass}，
     * Spring 直接尝试注入不存在的 bean，刷新 ApplicationContext 失败。
     * </p>
     * <p>
     * 修复后：方法级 {@code @ConditionalOnBean(MeterRegistry.class)} 跳过该 bean，
     * MQ 指标自动降级为仅写内存记录器。
     * </p>
     */
    @Test
    void shouldSkipMicrometerMqMetricsWhenMeterRegistryBeanAbsent() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MicrometerMqMetrics.class);
        });
    }

    /**
     * Regression for the {@code NoClassDefFoundError: io/micrometer/core/instrument/MeterRegistry}
     * scenario when {@code micrometer-core} is removed from the classpath. The bean was moved into
     * a nested {@code @Configuration} static class with {@code @ConditionalOnClass} at the class
     * level so Spring Boot resolves the condition through ASM without triggering JVM class loading.
     * 当 {@code micrometer-core} 从 classpath 上移除时，自动配置必须仍然能正常加载，
     * 只是不产出 {@link MicrometerMqMetrics}。
     */
    @Test
    void shouldStartContextWhenMicrometerCoreIsAbsent() {
        contextRunner.withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.containsBean("micrometerMqMetrics")).isFalse();
                });
    }
}
