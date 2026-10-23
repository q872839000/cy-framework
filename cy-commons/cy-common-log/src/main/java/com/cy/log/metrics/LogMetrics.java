package com.cy.log.metrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 日志组件指标收集器。
 *
 * @author pcy
 * @date 2026/04/10
 */
public final class LogMetrics {

    private static final LongAdder PUBLISH_SUCCESS = new LongAdder();
    private static final LongAdder PUBLISH_FAIL = new LongAdder();
    private static final LongAdder PERSIST_SUCCESS = new LongAdder();
    private static final LongAdder PERSIST_FAIL = new LongAdder();
    private static final LongAdder ACCESS_PUBLISH_SUCCESS = new LongAdder();
    private static final LongAdder ACCESS_PUBLISH_FAIL = new LongAdder();
    private static final LongAdder ACCESS_PERSIST_SUCCESS = new LongAdder();
    private static final LongAdder ACCESS_PERSIST_FAIL = new LongAdder();
    private static final LongAdder ACCESS_SAMPLED_OUT = new LongAdder();
    private static final LongAdder DISPATCH_ACCEPTED = new LongAdder();
    private static final LongAdder ACCESS_DISPATCH_ACCEPTED = new LongAdder();
    private static final ConcurrentMap<String, LaneMetrics> LANE_METRICS = new ConcurrentHashMap<>();

    private LogMetrics() {
    }

    public static void publishSuccess() {
        PUBLISH_SUCCESS.increment();
    }

    public static void publishFail() {
        PUBLISH_FAIL.increment();
    }

    public static void persistSuccess() {
        PERSIST_SUCCESS.increment();
    }

    public static void persistFail() {
        PERSIST_FAIL.increment();
    }

    public static void accessPublishSuccess() {
        ACCESS_PUBLISH_SUCCESS.increment();
    }

    public static void accessPublishFail() {
        ACCESS_PUBLISH_FAIL.increment();
    }

    public static void accessPersistSuccess() {
        ACCESS_PERSIST_SUCCESS.increment();
    }

    public static void accessPersistFail() {
        ACCESS_PERSIST_FAIL.increment();
    }

    public static void accessSampledOut() {
        ACCESS_SAMPLED_OUT.increment();
    }

    public static void dispatchAccepted() {
        DISPATCH_ACCEPTED.increment();
    }

    public static void accessDispatchAccepted() {
        ACCESS_DISPATCH_ACCEPTED.increment();
    }

    public static void accepted(String lane) {
        lane(lane).accepted.increment();
    }

    public static void dropped(String lane) {
        lane(lane).dropped.increment();
    }

    public static void queueRejected(String lane) {
        lane(lane).queueRejected.increment();
    }

    public static void breakerRejected(String lane) {
        lane(lane).breakerRejected.increment();
    }

    public static void fallbackSuccess(String lane) {
        lane(lane).fallbackSuccess.increment();
    }

    public static void fallbackFail(String lane) {
        lane(lane).fallbackFail.increment();
    }

    public static void queueDepth(String lane, long value) {
        lane(lane).queueDepth.set(value);
    }

    public static void queueCapacity(String lane, long value) {
        lane(lane).queueCapacity.set(value);
    }

    public static void inFlight(String lane, long value) {
        lane(lane).inFlight.set(value);
    }

    public static void breakerState(String lane, long value) {
        lane(lane).breakerState.set(value);
    }

    public static void degradeLevel(String lane, long value) {
        lane(lane).degradeLevel.set(value);
    }

    public static void lastPublishLatency(String lane, long value) {
        lane(lane).lastPublishLatency.set(value);
    }

    public static long getPublishSuccessCount() {
        return PUBLISH_SUCCESS.sum();
    }

    public static long getPublishFailCount() {
        return PUBLISH_FAIL.sum();
    }

    public static long getPersistSuccessCount() {
        return PERSIST_SUCCESS.sum();
    }

    public static long getPersistFailCount() {
        return PERSIST_FAIL.sum();
    }

    public static long getAccessPublishSuccessCount() {
        return ACCESS_PUBLISH_SUCCESS.sum();
    }

    public static long getAccessPublishFailCount() {
        return ACCESS_PUBLISH_FAIL.sum();
    }

    public static long getAccessPersistSuccessCount() {
        return ACCESS_PERSIST_SUCCESS.sum();
    }

    public static long getAccessPersistFailCount() {
        return ACCESS_PERSIST_FAIL.sum();
    }

    public static long getAccessSampledOutCount() {
        return ACCESS_SAMPLED_OUT.sum();
    }

    public static long getDispatchAcceptedCount() {
        return DISPATCH_ACCEPTED.sum();
    }

    public static long getAccessDispatchAcceptedCount() {
        return ACCESS_DISPATCH_ACCEPTED.sum();
    }

    public static long getLaneAcceptedCount(String lane) {
        return lane(lane).accepted.sum();
    }

    public static long getLaneDroppedCount(String lane) {
        return lane(lane).dropped.sum();
    }

    public static long getLaneQueueRejectedCount(String lane) {
        return lane(lane).queueRejected.sum();
    }

    public static long getLaneBreakerRejectedCount(String lane) {
        return lane(lane).breakerRejected.sum();
    }

    public static long getLaneFallbackSuccessCount(String lane) {
        return lane(lane).fallbackSuccess.sum();
    }

    public static long getLaneFallbackFailCount(String lane) {
        return lane(lane).fallbackFail.sum();
    }

    public static long getLaneQueueDepth(String lane) {
        return lane(lane).queueDepth.get();
    }

    public static long getLaneQueueCapacity(String lane) {
        return lane(lane).queueCapacity.get();
    }

    public static long getLaneInFlight(String lane) {
        return lane(lane).inFlight.get();
    }

    public static long getLaneBreakerState(String lane) {
        return lane(lane).breakerState.get();
    }

    public static long getLaneDegradeLevel(String lane) {
        return lane(lane).degradeLevel.get();
    }

    public static long getLaneLastPublishLatency(String lane) {
        return lane(lane).lastPublishLatency.get();
    }

    private static LaneMetrics lane(String lane) {
        return LANE_METRICS.computeIfAbsent(lane, key -> new LaneMetrics());
    }

    private static final class LaneMetrics {

        private final LongAdder accepted = new LongAdder();
        private final LongAdder dropped = new LongAdder();
        private final LongAdder queueRejected = new LongAdder();
        private final LongAdder breakerRejected = new LongAdder();
        private final LongAdder fallbackSuccess = new LongAdder();
        private final LongAdder fallbackFail = new LongAdder();
        private final AtomicLong queueDepth = new AtomicLong();
        private final AtomicLong queueCapacity = new AtomicLong();
        private final AtomicLong inFlight = new AtomicLong();
        private final AtomicLong breakerState = new AtomicLong();
        private final AtomicLong degradeLevel = new AtomicLong();
        private final AtomicLong lastPublishLatency = new AtomicLong();
    }
}
