package com.cy.log.publish;

import com.cy.mq.core.MqPublishRequest;
import com.cy.mq.core.MqTemplate;
import lombok.RequiredArgsConstructor;

/**
 * 基于 MQ 的日志传输实现。
 *
 * @author pcy
 * @date 2026/04/14
 */
@RequiredArgsConstructor
public class MqLogTransport implements LogTransport {

    /**
     * MQ 模板。
     */
    private final MqTemplate mqTemplate;

    @Override
    public void publish(LogPublishTask task) {
        mqTemplate.publish(MqPublishRequest.builder()
                .topic(task.getTopic())
                .payload(task.getRecord())
                .deliveryMode(task.getDeliveryMode())
                .build());
    }
}
