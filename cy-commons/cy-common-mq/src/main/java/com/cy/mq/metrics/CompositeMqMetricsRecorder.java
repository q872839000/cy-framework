package com.cy.mq.metrics;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 组合 MQ 指标记录器。
 * <p>
 * 将指标同时记录到多个记录器（如内存 + Micrometer），确保指标的完整性和可用性。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
public class CompositeMqMetricsRecorder implements MqMetricsRecorder {

    private final List<MqMetricsRecorder> recorders = new CopyOnWriteArrayList<>();

    public void addRecorder(MqMetricsRecorder recorder) {
        if (recorder != null && !recorders.contains(recorder)) {
            recorders.add(recorder);
        }
    }

    public void removeRecorder(MqMetricsRecorder recorder) {
        recorders.remove(recorder);
    }

    @Override
    public void recordPublishSuccess(MqMetricsContext context) {
        for (MqMetricsRecorder recorder : recorders) {
            try {
                recorder.recordPublishSuccess(context);
            } catch (Exception e) {
                // 指标记录失败不应影响业务
            }
        }
    }

    @Override
    public void recordPublishFailure(MqMetricsContext context) {
        for (MqMetricsRecorder recorder : recorders) {
            try {
                recorder.recordPublishFailure(context);
            } catch (Exception e) {
                // 指标记录失败不应影响业务
            }
        }
    }

    @Override
    public void recordPublishDuration(MqMetricsContext context, long durationMs) {
        for (MqMetricsRecorder recorder : recorders) {
            try {
                recorder.recordPublishDuration(context, durationMs);
            } catch (Exception e) {
                // 指标记录失败不应影响业务
            }
        }
    }

    @Override
    public void recordConsumeSuccess(MqMetricsContext context) {
        for (MqMetricsRecorder recorder : recorders) {
            try {
                recorder.recordConsumeSuccess(context);
            } catch (Exception e) {
                // 指标记录失败不应影响业务
            }
        }
    }

    @Override
    public void recordConsumeFailure(MqMetricsContext context) {
        for (MqMetricsRecorder recorder : recorders) {
            try {
                recorder.recordConsumeFailure(context);
            } catch (Exception e) {
                // 指标记录失败不应影响业务
            }
        }
    }

    @Override
    public void recordConsumeDuration(MqMetricsContext context, long durationMs) {
        for (MqMetricsRecorder recorder : recorders) {
            try {
                recorder.recordConsumeDuration(context, durationMs);
            } catch (Exception e) {
                // 指标记录失败不应影响业务
            }
        }
    }
}
