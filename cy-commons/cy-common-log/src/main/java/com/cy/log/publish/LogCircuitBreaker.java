package com.cy.log.publish;

import com.cy.log.config.LogProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 日志发布熔断器。
 *
 * @author pcy
 * @date 2026/04/14
 */
@Slf4j
public class LogCircuitBreaker {

    /**
     * 熔断状态。
     */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final LogProperties.Breaker config;

    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

    private final AtomicInteger totalCalls = new AtomicInteger();

    private final AtomicInteger failedCalls = new AtomicInteger();

    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    private final AtomicLong openUntil = new AtomicLong();

    private final AtomicInteger halfOpenPermits = new AtomicInteger();

    @Getter
    private volatile State state = State.CLOSED;

    public LogCircuitBreaker(LogProperties.Breaker config) {
        this.config = config;
    }

    /**
     * 是否允许新请求进入。
     */
    public boolean allowRequest() {
        long now = System.currentTimeMillis();
        if (state == State.OPEN) {
            if (now < openUntil.get()) {
                return false;
            }
            transitionToHalfOpen();
        }

        if (state == State.HALF_OPEN) {
            int permits = halfOpenPermits.getAndDecrement();
            if (permits <= 0) {
                halfOpenPermits.incrementAndGet();
                return false;
            }
        }
        return true;
    }

    /**
     * 记录成功。
     */
    public synchronized void recordSuccess() {
        rotateWindowIfNeeded();
        totalCalls.incrementAndGet();
        consecutiveFailures.set(0);
        if (state == State.HALF_OPEN) {
            transitionToClosed();
        }
    }

    /**
     * 记录失败。
     */
    public synchronized void recordFailure() {
        rotateWindowIfNeeded();
        totalCalls.incrementAndGet();
        failedCalls.incrementAndGet();
        int failures = consecutiveFailures.incrementAndGet();

        if (state == State.HALF_OPEN) {
            transitionToOpen();
            return;
        }

        if (failures >= Math.max(1, config.getConsecutiveFailures())) {
            transitionToOpen();
            return;
        }

        int calls = totalCalls.get();
        if (calls < Math.max(1, config.getMinimumCalls())) {
            return;
        }

        double failureRate = (double) failedCalls.get() / (double) calls;
        if (failureRate >= config.getFailureRateThreshold()) {
            transitionToOpen();
        }
    }

    /**
     * 获取状态编码，便于指标输出。
     */
    public long stateCode() {
        return switch (state) {
            case CLOSED -> 0L;
            case OPEN -> 1L;
            case HALF_OPEN -> 2L;
        };
    }

    private void rotateWindowIfNeeded() {
        long now = System.currentTimeMillis();
        long currentStart = windowStart.get();
        if (now - currentStart < Math.max(1000L, config.getWindowMs())) {
            return;
        }
        windowStart.set(now);
        totalCalls.set(0);
        failedCalls.set(0);
    }

    private void transitionToOpen() {
        state = State.OPEN;
        openUntil.set(System.currentTimeMillis() + Math.max(1000L, config.getOpenDurationMs()));
        halfOpenPermits.set(0);
        log.warn("日志发布熔断已打开: openDurationMs={}", config.getOpenDurationMs());
    }

    private void transitionToHalfOpen() {
        state = State.HALF_OPEN;
        halfOpenPermits.set(Math.max(1, config.getHalfOpenPermits()));
        totalCalls.set(0);
        failedCalls.set(0);
        consecutiveFailures.set(0);
        log.info("日志发布熔断进入半开探测状态");
    }

    private void transitionToClosed() {
        state = State.CLOSED;
        openUntil.set(0L);
        halfOpenPermits.set(0);
        totalCalls.set(0);
        failedCalls.set(0);
        consecutiveFailures.set(0);
        log.info("日志发布熔断已恢复关闭");
    }
}
