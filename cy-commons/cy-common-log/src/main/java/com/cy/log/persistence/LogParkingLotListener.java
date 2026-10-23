package com.cy.log.persistence;

import com.cy.log.config.LogProperties;
import com.cy.log.config.LogTopicNaming;
import com.cy.log.persistence.dlq.LogDeadLetterBatch;
import com.cy.mq.annotation.MqListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Parking Lot 监听器 - 监控最终失败的日志。
 * <p>
 * 当死信回放达到最大次数后，日志会进入 parking lot。
 * 该监听器负责记录和告警，防止 parking lot 无限堆积。
 *
 * @author pcy
 * @date 2026/04/16
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cy.log.persistence", name = "enabled", havingValue = "true")
public class LogParkingLotListener {

    private final LogProperties properties;
    private final LogTopicNaming topicNaming;

    /**
     * 处理业务日志 parking lot 消息。
     */
    @MqListener(
        topic = "#{logTopicNaming.businessParkingLotTopic()}",
        groupId = "log-parking-lot-monitor"
    )
    public void handleBusinessParkingLot(LogDeadLetterBatch batch) {
        if (batch == null || batch.getRecords() == null) {
            return;
        }

        log.error("业务日志进入 parking lot，需要人工介入: " +
                "batchType={}, size={}, replayAttempt={}, failedAt={}, lastReplayedAt={}, error={}",
            batch.getBatchType(),
            batch.getRecords().size(),
            batch.getReplayAttempt(),
            batch.getFailedAt(),
            batch.getLastReplayedAt(),
            batch.getErrorMessage());

        // TODO: 发送告警通知
        // 可以集成钉钉、企业微信、Slack、邮件等告警渠道
        // alertService.sendAlert("日志 parking lot 堆积", buildAlertMessage(batch));
    }

    /**
     * 处理 ACCESS 日志 parking lot 消息。
     */
    @MqListener(
        topic = "#{logTopicNaming.accessParkingLotTopic()}",
        groupId = "log-access-parking-lot-monitor"
    )
    public void handleAccessParkingLot(LogDeadLetterBatch batch) {
        if (batch == null || batch.getRecords() == null) {
            return;
        }

        log.error("ACCESS 日志进入 parking lot: " +
                "size={}, replayAttempt={}, failedAt={}, error={}",
            batch.getRecords().size(),
            batch.getReplayAttempt(),
            batch.getFailedAt(),
            batch.getErrorMessage());

        // TODO: 发送告警通知
    }

    /**
     * 构建告警消息（示例）。
     */
    private String buildAlertMessage(LogDeadLetterBatch batch) {
        return String.format(
            "日志持久化最终失败\n" +
            "类型: %s\n" +
            "数量: %d\n" +
            "重试次数: %d\n" +
            "首次失败: %s\n" +
            "最后重试: %s\n" +
            "错误信息: %s\n" +
            "目标表: %s",
            batch.getBatchType(),
            batch.getRecords() != null ? batch.getRecords().size() : 0,
            batch.getReplayAttempt(),
            batch.getFailedAt(),
            batch.getLastReplayedAt(),
            batch.getErrorMessage(),
            batch.getTargetTables()
        );
    }
}
