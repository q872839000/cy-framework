package com.cy.log.publish;

import com.cy.log.config.LogProperties;
import com.cy.log.metrics.LogMetrics;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 日志发布车道调度器。
 *
 * @author pcy
 * @date 2026/04/14
 */
@Slf4j
public class LogDispatcher implements AutoCloseable {

    @Getter
    private final LogLane lane;

    private final LogProperties.Lane laneConfig;

    private final LogProperties.Degrade degradeConfig;

    private final LogTransport transport;

    private final LogFallbackStore fallbackStore;

    private final LogCircuitBreaker breaker;

    private final ThreadPoolExecutor executor;

    private final AtomicInteger inFlight = new AtomicInteger();

    public LogDispatcher(LogLane lane,
                         LogProperties.Lane laneConfig,
                         LogProperties.Degrade degradeConfig,
                         LogProperties.Breaker breakerConfig,
                         LogTransport transport,
                         LogFallbackStore fallbackStore) {
        this.lane = Objects.requireNonNull(lane, "lane must not be null");
        this.laneConfig = Objects.requireNonNull(laneConfig, "laneConfig must not be null");
        this.degradeConfig = Objects.requireNonNull(degradeConfig, "degradeConfig must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.fallbackStore = Objects.requireNonNull(fallbackStore, "fallbackStore must not be null");
        this.breaker = new LogCircuitBreaker(Objects.requireNonNull(breakerConfig, "breakerConfig must not be null"));
        this.executor = createExecutor();
        refreshMetrics();
    }

    /**
     * 提交日志发布任务。
     */
    public boolean dispatch(LogPublishTask task) {
        if (task == null || task.getRecord() == null || !laneConfig.isEnabled()) {
            return false;
        }

        if (!breaker.allowRequest()) {
            reject(task, "breaker-open", false);
            return false;
        }

        try {
            executor.execute(() -> publishInternal(task));
            markAccepted(task.isAccessLog());
            refreshMetrics();
            return true;
        } catch (RejectedExecutionException exception) {
            reject(task, "queue-full", laneConfig.isFallbackOnReject());
            refreshMetrics();
            return false;
        }
    }

    /**
     * 获取当前降级等级。
     */
    public LogDegradeLevel currentDegradeLevel() {
        if (breaker.getState() == LogCircuitBreaker.State.OPEN) {
            return LogDegradeLevel.EMERGENCY;
        }
        double queueUsage = queueUsage();
        if (!degradeConfig.isEnabled()) {
            return LogDegradeLevel.NORMAL;
        }
        if (queueUsage >= degradeConfig.getEmergencyQueueUsage()) {
            return LogDegradeLevel.EMERGENCY;
        }
        if (queueUsage >= degradeConfig.getPressureQueueUsage()) {
            return LogDegradeLevel.PRESSURE;
        }
        return LogDegradeLevel.NORMAL;
    }

    private void publishInternal(LogPublishTask task) {
        inFlight.incrementAndGet();
        refreshMetrics();
        long start = System.currentTimeMillis();
        try {
            transport.publish(task);
            breaker.recordSuccess();
            markSuccess(task.isAccessLog());
        } catch (Exception exception) {
            breaker.recordFailure();
            markFailure(task.isAccessLog());
            boolean fallbackAttempted = laneConfig.isFallbackOnFailure();
            boolean fallbackSuccess = fallbackAttempted
                    && fallbackStore.store(task, "publish-failure", exception);
            recordFallbackMetrics(fallbackAttempted, fallbackSuccess);
            log.warn("日志发布失败: lane={}, topic={}, traceId={}, fallbackAttempted={}, fallbackSuccess={}",
                    lane, task.getTopic(),
                    task.getRecord() != null ? task.getRecord().getTraceId() : null,
                    fallbackAttempted, fallbackSuccess, exception);
        } finally {
            long duration = System.currentTimeMillis() - start;
            LogMetrics.lastPublishLatency(lane.metricKey(), duration);
            inFlight.decrementAndGet();
            refreshMetrics();
        }
    }

    private void reject(LogPublishTask task, String reason, boolean allowFallback) {
        markFailure(task.isAccessLog());
        LogMetrics.dropped(lane.metricKey());
        if ("breaker-open".equals(reason)) {
            LogMetrics.breakerRejected(lane.metricKey());
        } else {
            LogMetrics.queueRejected(lane.metricKey());
        }

        boolean fallbackSuccess = allowFallback && fallbackStore.store(task, reason, null);
        recordFallbackMetrics(allowFallback, fallbackSuccess);
        log.warn("日志发布被拒绝: lane={}, topic={}, reason={}, fallbackAttempted={}, fallbackSuccess={}",
                lane, task.getTopic(), reason, allowFallback, fallbackSuccess);
    }

    private void recordFallbackMetrics(boolean fallbackAttempted, boolean fallbackSuccess) {
        if (fallbackSuccess) {
            LogMetrics.fallbackSuccess(lane.metricKey());
        } else if (fallbackAttempted) {
            LogMetrics.fallbackFail(lane.metricKey());
        }
    }

    private void markAccepted(boolean accessLog) {
        LogMetrics.accepted(lane.metricKey());
        if (accessLog) {
            LogMetrics.accessDispatchAccepted();
            return;
        }
        LogMetrics.dispatchAccepted();
    }

    private void markSuccess(boolean accessLog) {
        if (accessLog) {
            LogMetrics.accessPublishSuccess();
            return;
        }
        LogMetrics.publishSuccess();
    }

    private void markFailure(boolean accessLog) {
        if (accessLog) {
            LogMetrics.accessPublishFail();
            return;
        }
        LogMetrics.publishFail();
    }

    private double queueUsage() {
        int capacity = Math.max(1, laneConfig.getQueueCapacity());
        return (double) executor.getQueue().size() / (double) capacity;
    }

    private ThreadPoolExecutor createExecutor() {
        int coreThreads = Math.max(1, laneConfig.getCoreThreads());
        int maxThreads = Math.max(coreThreads, laneConfig.getMaxThreads());
        long keepAliveMs = Math.max(1000L, laneConfig.getKeepAliveMs());
        int queueCapacity = Math.max(1, laneConfig.getQueueCapacity());

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("cy-log-" + lane.metricKey() + "-" + thread.threadId());
            thread.setDaemon(true);
            return thread;
        };

        return new ThreadPoolExecutor(
                coreThreads,
                maxThreads,
                keepAliveMs,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private void refreshMetrics() {
        LogMetrics.queueDepth(lane.metricKey(), executor.getQueue().size());
        LogMetrics.queueCapacity(lane.metricKey(), laneConfig.getQueueCapacity());
        LogMetrics.inFlight(lane.metricKey(), inFlight.get());
        LogMetrics.breakerState(lane.metricKey(), breaker.stateCode());
        LogMetrics.degradeLevel(lane.metricKey(), currentDegradeLevel().ordinal());
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
