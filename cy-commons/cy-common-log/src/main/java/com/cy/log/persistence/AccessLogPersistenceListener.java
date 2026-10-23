package com.cy.log.persistence;

import com.cy.log.config.LogProperties;
import com.cy.log.config.LogTopicNaming;
import com.cy.log.core.LogRecord;
import com.cy.log.metrics.LogMetrics;
import com.cy.log.persistence.dlq.LogDeadLetterBatch;
import com.cy.log.persistence.mapper.LogMapper;
import com.cy.mq.annotation.MqListener;
import com.cy.mq.core.MqTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

/**
 * ACCESS 日志持久化监听器。
 *
 * @author pcy
 * @date 2026/04/10
 */
public class AccessLogPersistenceListener extends AbstractLogPersistenceListener {

    /**
     * 日志主题命名解析器。
     */
    private final LogTopicNaming topicNaming;

    public AccessLogPersistenceListener(LogMapper logMapper,
                                        LogTableRouter accessTableRouter,
                                        MqTemplate mqTemplate,
                                        LogProperties properties,
                                        LogTopicNaming topicNaming,
                                        PlatformTransactionManager transactionManager) {
        super(logMapper, accessTableRouter, mqTemplate, properties, transactionManager);
        this.topicNaming = topicNaming;
    }

    /**
     * 批量处理 ACCESS 日志消息。
     *
     * @param logRecords ACCESS 日志记录
     */
    @MqListener(topic = "#{@logTopicNaming.accessTopic()}",
            groupId = "${cy.log.access.group-id:log-access-persistence}",
            batch = true)
    public void onAccessLogRecords(List<LogRecord> logRecords) {
        persistBatch(logRecords);
    }

    /**
     * 处理 ACCESS 日志死信回放消息。
     *
     * @param deadLetterBatch 死信批次
     */
    @MqListener(topic = "#{@logTopicNaming.accessDeadLetterTopic()}",
            groupId = "${cy.log.access.dead-letter-replay-group-id:log-access-dlq-replay}")
    public void onDeadLetterBatch(LogDeadLetterBatch deadLetterBatch) {
        replayDeadLetterBatch(deadLetterBatch);
    }

    @Override
    protected String logTypeName() {
        return "ACCESS";
    }

    /**
     * ACCESS 日志死信 Topic。
     */
    @Override
    protected String deadLetterTopic() {
        return topicNaming.accessDeadLetterTopic();
    }

    /**
     * ACCESS 日志死信最终停放 Topic。
     */
    @Override
    protected String parkingLotTopic() {
        return topicNaming.accessParkingLotTopic();
    }

    @Override
    protected void markPersistSuccess(int count) {
        for (int i = 0; i < count; i++) {
            LogMetrics.accessPersistSuccess();
        }
    }

    @Override
    protected void markPersistFail(int count) {
        for (int i = 0; i < count; i++) {
            LogMetrics.accessPersistFail();
        }
    }
}
