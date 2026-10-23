package com.cy.mq.config;

import com.cy.mq.core.MqDriverRegistry;
import com.cy.mq.health.MqHealthIndicator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * MQ 健康检查自动配置。
 * <p>
 * 当 Spring Boot Actuator 存在时，自动注册 MQ 健康检查指示器。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
@Slf4j
@AutoConfiguration(after = MqAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
public class MqHealthAutoConfiguration {

    @Bean
    public MqHealthIndicator mqHealthIndicator(MqDriverRegistry driverRegistry) {
        log.info("MQ 健康检查已启用，可通过 /actuator/health 端点访问");
        return new MqHealthIndicator(driverRegistry);
    }
}
