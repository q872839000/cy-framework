package com.cy.log.publish;

import com.cy.log.core.LogRecord;
import com.cy.mq.core.MqDeliveryMode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 日志发布任务。
 *
 * @author pcy
 * @date 2026/04/14
 */
@Getter
@RequiredArgsConstructor
public class LogPublishTask {

    /**
     * 目标车道。
     */
    private final LogLane lane;

    /**
     * 目标 Topic。
     */
    private final String topic;

    /**
     * 日志记录。
     */
    private final LogRecord record;

    /**
     * MQ 投递模式。
     */
    private final MqDeliveryMode deliveryMode;

    /**
     * 是否为 ACCESS 日志。
     */
    private final boolean accessLog;
}
