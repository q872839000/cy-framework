package com.cy.mq.config;

import com.cy.mq.metrics.MicrometerMqMetrics;
import com.cy.mq.metrics.MqMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 监控指标自动配置。
 * <p>
 * 当 Micrometer 在 classpath 且容器内存在 {@code MeterRegistry} bean 时，自动启用
 * Micrometer 集成，将指标注册到 {@code MeterRegistry}。两种条件任一不满足都安全跳过，
 * 与 {@code cy-common-mq} pom 中 {@code micrometer-core} 标记为 {@code optional=true}
 * 的真实语义保持一致。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
@Slf4j
@AutoConfiguration
public class MqMetricsAutoConfiguration {

    /**
     * Micrometer MQ 指标桥接装配。
     * <p>
     * 必须独立成一个嵌套 {@code @Configuration} 静态类，把
     * {@link ConditionalOnClass} 落到 <b>类级别</b>。原因：当 {@code micrometer-core}
     * 不在 classpath 时，方法级 {@code @ConditionalOnClass(MeterRegistry.class)}
     * 仍然会被 Spring 在评估 {@link ConditionalOnBean} 时走到
     * {@code MqMetricsAutoConfiguration.getDeclaredMethods()}，JVM 强制解析方法签名里的
     * {@code MeterRegistry} 参数类型并抛 {@code NoClassDefFoundError}。
     * 落到嵌套类的类级别后，Spring Boot 通过 ASM 读取条件元数据，缺类时
     * 整个嵌套类被跳过，不进类加载、不解析参数类型。
     * </p>
     * <p>
     * 同时方法级 {@link ConditionalOnBean} 保证仅当宿主应用真正注册了
     * {@code MeterRegistry} bean（例如引入 {@code spring-boot-starter-actuator}
     * 后由 Spring Boot 的 {@code MetricsAutoConfiguration} 自动装配）时，才装配
     * {@link MicrometerMqMetrics}；否则 MQ 指标自动降级为仅写入内存记录器
     * （{@code com.cy.mq.metrics.InMemoryMqMetricsRecorder}）。
     * </p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(io.micrometer.core.instrument.MeterRegistry.class)
    static class MicrometerMqMetricsConfiguration {

        @Bean
        @ConditionalOnBean(io.micrometer.core.instrument.MeterRegistry.class)
        @ConditionalOnMissingBean
        public MicrometerMqMetrics micrometerMqMetrics(io.micrometer.core.instrument.MeterRegistry meterRegistry) {
            MicrometerMqMetrics metrics = new MicrometerMqMetrics(meterRegistry);
            MqMetrics.registerMicrometerRecorder(metrics);
            log.info("MQ Micrometer 监控指标已启用，指标将同时记录到内存和 Micrometer");
            return metrics;
        }
    }
}
