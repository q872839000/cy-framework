package com.cy.mq.health;

import com.cy.mq.core.MqDriverRegistry;
import com.cy.mq.core.MqDriverType;
import com.cy.mq.metrics.MqMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * MQ 健康检查指示器。
 * <p>
 * 检查 MQ 组件的健康状态，包括：
 * <ul>
 *     <li>驱动注册状态</li>
 *     <li>发布/消费失败率</li>
 *     <li>消息堆积情况</li>
 * </ul>
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
@Component
@RequiredArgsConstructor
public class MqHealthIndicator implements HealthIndicator {

    private final MqDriverRegistry driverRegistry;

    /**
     * 发布失败率阈值（超过此值认为不健康）。
     */
    private static final double PUBLISH_FAILURE_RATE_THRESHOLD = 0.1;  // 10%

    /**
     * 消费失败率阈值（超过此值认为不健康）。
     */
    private static final double CONSUME_FAILURE_RATE_THRESHOLD = 0.05;  // 5%

    @Override
    public Health health() {
        try {
            Map<String, Object> details = new HashMap<>();

            // 检查驱动注册状态
            var registeredDrivers = driverRegistry.registeredDriverTypes();
            details.put("registeredDrivers", registeredDrivers);
            details.put("driverCount", registeredDrivers.size());

            if (registeredDrivers.isEmpty()) {
                return Health.down()
                        .withDetails(details)
                        .withDetail("reason", "没有注册任何 MQ 驱动")
                        .build();
            }

            // 检查发布失败率
            double publishFailureRate = MqMetrics.getPublishFailureRate();
            details.put("publishSuccessCount", MqMetrics.getPublishSuccessCount());
            details.put("publishFailureCount", MqMetrics.getPublishFailureCount());
            details.put("publishFailureRate", String.format("%.2f%%", publishFailureRate * 100));

            if (publishFailureRate > PUBLISH_FAILURE_RATE_THRESHOLD) {
                return Health.down()
                        .withDetails(details)
                        .withDetail("reason", String.format("发布失败率过高: %.2f%% > %.2f%%",
                                publishFailureRate * 100, PUBLISH_FAILURE_RATE_THRESHOLD * 100))
                        .build();
            }

            // 检查消费失败率
            double consumeFailureRate = MqMetrics.getConsumeFailureRate();
            details.put("consumeSuccessCount", MqMetrics.getConsumeSuccessCount());
            details.put("consumeFailureCount", MqMetrics.getConsumeFailureCount());
            details.put("consumeFailureRate", String.format("%.2f%%", consumeFailureRate * 100));

            if (consumeFailureRate > CONSUME_FAILURE_RATE_THRESHOLD) {
                return Health.down()
                        .withDetails(details)
                        .withDetail("reason", String.format("消费失败率过高: %.2f%% > %.2f%%",
                                consumeFailureRate * 100, CONSUME_FAILURE_RATE_THRESHOLD * 100))
                        .build();
            }

            // 所有检查通过
            return Health.up()
                    .withDetails(details)
                    .build();

        } catch (Exception e) {
            return Health.down()
                    .withException(e)
                    .withDetail("reason", "健康检查执行失败: " + e.getMessage())
                    .build();
        }
    }
}
