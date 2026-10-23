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
 * 业务日志持久化监听器。
 *
 * @author pcy
 * @date 2026/01/29
 */
public class LogPersistenceListener extends AbstractLogPersistenceListener {

    /**
     * 日志主题命名解析器。
     */
    private final LogTopicNaming topicNaming;

    public LogPersistenceListener(LogMapper logMapper,
                                  LogTableRouter tableRouter,
                                  MqTemplate mqTemplate,
                                  LogProperties properties,
                                  LogTopicNaming topicNaming,
                                  PlatformTransactionManager transactionManager) {
        super(logMapper, tableRouter, mqTemplate, properties, transactionManager);
        this.topicNaming = topicNaming;
    }

    /**
     * 批量处理业务日志消息。
     *
     * @param logRecords 日志记录
     */
    @MqListener(topic = "#{@logTopicNaming.businessTopic()}",
            groupId = "${cy.log.mq.group-id:log-persistence}",
            batch = true)
    public void onLogRecords(List<LogRecord> logRecords) {
        persistBatch(logRecords);
    }

    /**
     * 处理业务日志死信回放消息。
     *
     * @param deadLetterBatch 死信批次
     */
    @MqListener(topic = "#{@logTopicNaming.businessDeadLetterTopic()}",
            groupId = "${cy.log.mq.dead-letter-replay-group-id:log-dlq-replay}")
    public void onDeadLetterBatch(LogDeadLetterBatch deadLetterBatch) {
        replayDeadLetterBatch(deadLetterBatch);
    }

    @Override
    protected String logTypeName() {
        return "业务";
    }

    /**
     * 业务日志死信 Topic。
     */
    @Override
    protected String deadLetterTopic() {
        return topicNaming.businessDeadLetterTopic();
    }

    /**
     * 业务日志死信最终停放 Topic。
     */
    @Override
    protected String parkingLotTopic() {
        return topicNaming.businessParkingLotTopic();
    }

    @Override
    protected void markPersistSuccess(int count) {
        for (int i = 0; i < count; i++) {
            LogMetrics.persistSuccess();
        }
    }

    @Override
    protected void markPersistFail(int count) {
        for (int i = 0; i < count; i++) {
            LogMetrics.persistFail();
        }
    }
}
