package com.cy.log.core;

import com.cy.log.config.LogProperties;
import com.cy.log.config.LogTopicNaming;
import com.cy.log.payload.LogRecordNormalizer;
import com.cy.log.publish.LogDegradeLevel;
import com.cy.log.publish.LogDispatcher;
import com.cy.log.publish.LogLane;
import com.cy.log.publish.LogPublishTask;
import com.cy.log.publish.LogTransport;
import com.cy.log.publish.LogFallbackStore;
import lombok.extern.slf4j.Slf4j;

/**
 * 日志发布器。
 * <p>
 * 该组件负责把业务线程中的日志事件交给具备有界队列、熔断与降级能力的发布车道处理，
 * 从而保证日志发布对主业务线程零阻塞、可限流、可观测、可降级。
 * </p>
 *
 * @author pcy
 * @date 2026/01/29
 */
@Slf4j
public class LogPublisher implements AutoCloseable {

    /**
     * 日志配置。
     */
    private final LogProperties properties;

    /**
     * 日志主题命名解析器。
     */
    private final LogTopicNaming topicNaming;

    /**
     * 业务日志发布车道。
     */
    private final LogDispatcher businessDispatcher;

    /**
     * ACCESS 日志发布车道。
     */
    private final LogDispatcher accessDispatcher;

    /**
     * 发布前最终兜底归一化器（无论入口是否走过 {@code LogPayloadProcessor} 都会再次收敛）。
     */
    private final LogRecordNormalizer recordNormalizer;

    public LogPublisher(LogProperties properties, LogTopicNaming topicNaming, LogTransport transport, LogFallbackStore fallbackStore) {
        this(properties, topicNaming, transport, fallbackStore, new LogRecordNormalizer(properties));
    }

    public LogPublisher(LogProperties properties, LogTopicNaming topicNaming, LogTransport transport,
                        LogFallbackStore fallbackStore, LogRecordNormalizer recordNormalizer) {
        this.properties = properties;
        this.topicNaming = topicNaming;
        this.recordNormalizer = recordNormalizer != null ? recordNormalizer : new LogRecordNormalizer(properties);
        this.businessDispatcher = new LogDispatcher(
                LogLane.BUSINESS,
                properties.getPublish().getBusiness(),
                properties.getPublish().getDegrade(),
                properties.getPublish().getBreaker(),
                transport,
                fallbackStore
        );
        this.accessDispatcher = new LogDispatcher(
                LogLane.ACCESS,
                properties.getPublish().getAccess(),
                properties.getPublish().getDegrade(),
                properties.getPublish().getBreaker(),
                transport,
                fallbackStore
        );
    }

    /**
     * 发布业务日志。
     * <p>
     * 在投递前总会再次调用 {@link LogRecordNormalizer#normalize(LogRecord)}，
     * 即便上游忘记走 {@code LogPayloadProcessor} 也不会让超长 / 危险字符串透传到 MQ 与数据库。
     * </p>
     *
     * @param logRecord 日志记录
     */
    public void publish(LogRecord logRecord) {
        if (logRecord == null) {
            return;
        }
        recordNormalizer.normalize(logRecord);
        dispatch(new LogPublishTask(
                LogLane.BUSINESS,
                topicNaming.businessTopic(),
                logRecord,
                properties.getMq().getDeliveryMode(),
                false
        ));
    }

    /**
     * 发布 ACCESS 日志。
     * <p>
     * 同 {@link #publish(LogRecord)}：发布前统一兜底归一化。
     * </p>
     *
     * @param logRecord ACCESS 日志记录
     */
    public void publishAccess(LogRecord logRecord) {
        if (logRecord == null) {
            return;
        }
        recordNormalizer.normalize(logRecord);
        dispatch(new LogPublishTask(
                LogLane.ACCESS,
                topicNaming.accessTopic(),
                logRecord,
                properties.getAccess().getDeliveryMode(),
                true
        ));
    }

    /**
     * 获取 ACCESS 发布车道当前降级等级。
     */
    public LogDegradeLevel currentAccessDegradeLevel() {
        return accessDispatcher.currentDegradeLevel();
    }

    /**
     * 将日志任务投递到对应发布车道。
     */
    private void dispatch(LogPublishTask task) {
        if (task == null) {
            return;
        }
        LogDispatcher dispatcher = task.getLane() == LogLane.ACCESS ? accessDispatcher : businessDispatcher;
        boolean accepted = dispatcher.dispatch(task);
        if (!accepted) {
            log.debug("日志发布任务未被接收: lane={}, topic={}, traceId={}",
                    task.getLane(),
                    task.getTopic(),
                    task.getRecord() != null ? task.getRecord().getTraceId() : null);
        }
    }

    @Override
    public void close() {
        businessDispatcher.close();
        accessDispatcher.close();
    }
}
