package com.cy.log.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.function.Supplier;

/**
 * Micrometer 指标桥接。
 *
 * @author pcy
 * @date 2026/04/10
 */
public class LogMicrometerBridge {

    public LogMicrometerBridge(MeterRegistry registry) {
        register(registry, "cy.log.publish.success", LogMetrics::getPublishSuccessCount);
        register(registry, "cy.log.publish.fail", LogMetrics::getPublishFailCount);
        register(registry, "cy.log.persist.success", LogMetrics::getPersistSuccessCount);
        register(registry, "cy.log.persist.fail", LogMetrics::getPersistFailCount);
        register(registry, "cy.log.access.publish.success", LogMetrics::getAccessPublishSuccessCount);
        register(registry, "cy.log.access.publish.fail", LogMetrics::getAccessPublishFailCount);
        register(registry, "cy.log.access.persist.success", LogMetrics::getAccessPersistSuccessCount);
        register(registry, "cy.log.access.persist.fail", LogMetrics::getAccessPersistFailCount);
        register(registry, "cy.log.access.sampled.out", LogMetrics::getAccessSampledOutCount);
        register(registry, "cy.log.dispatch.accepted", LogMetrics::getDispatchAcceptedCount);
        register(registry, "cy.log.access.dispatch.accepted", LogMetrics::getAccessDispatchAcceptedCount);

        registerLane(registry, "business");
        registerLane(registry, "access");
    }

    private void registerLane(MeterRegistry registry, String lane) {
        register(registry, "cy.log.lane.accepted", lane, () -> LogMetrics.getLaneAcceptedCount(lane));
        register(registry, "cy.log.lane.dropped", lane, () -> LogMetrics.getLaneDroppedCount(lane));
        register(registry, "cy.log.lane.queue.rejected", lane, () -> LogMetrics.getLaneQueueRejectedCount(lane));
        register(registry, "cy.log.lane.breaker.rejected", lane, () -> LogMetrics.getLaneBreakerRejectedCount(lane));
        register(registry, "cy.log.lane.fallback.success", lane, () -> LogMetrics.getLaneFallbackSuccessCount(lane));
        register(registry, "cy.log.lane.fallback.fail", lane, () -> LogMetrics.getLaneFallbackFailCount(lane));
        register(registry, "cy.log.lane.queue.depth", lane, () -> LogMetrics.getLaneQueueDepth(lane));
        register(registry, "cy.log.lane.queue.capacity", lane, () -> LogMetrics.getLaneQueueCapacity(lane));
        register(registry, "cy.log.lane.inflight", lane, () -> LogMetrics.getLaneInFlight(lane));
        register(registry, "cy.log.lane.breaker.state", lane, () -> LogMetrics.getLaneBreakerState(lane));
        register(registry, "cy.log.lane.degrade.level", lane, () -> LogMetrics.getLaneDegradeLevel(lane));
        register(registry, "cy.log.lane.publish.latency.last", lane, () -> LogMetrics.getLaneLastPublishLatency(lane));
    }

    private static void register(MeterRegistry registry, String name, Supplier<Long> supplier) {
        Gauge.builder(name, () -> supplier.get().doubleValue()).register(registry);
    }

    private static void register(MeterRegistry registry, String name, String lane, Supplier<Long> supplier) {
        Gauge.builder(name, () -> supplier.get().doubleValue())
                .tag("lane", lane)
                .register(registry);
    }
}
