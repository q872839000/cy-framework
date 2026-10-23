package com.cy.log.core;

import com.cy.core.context.InvocationContext;
import com.cy.log.config.LogProperties;
import com.cy.log.payload.LogPayloadProcessor;
import com.cy.log.request.RequestInfoResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * 日志服务实现 - 提供编程式日志记录 API。
 *
 * @author pcy
 * @date 2026/04/06
 */
@Slf4j
public class LogServiceImpl implements LogService {

    private final LogPublisher logPublisher;
    private final LogRecordBuilder recordBuilder;
    private final LogPayloadProcessor payloadProcessor;

    public LogServiceImpl(LogPublisher logPublisher, LogProperties properties, String serviceName,
                          RequestInfoResolver requestInfoResolver, LogPayloadProcessor payloadProcessor) {
        this.logPublisher = logPublisher;
        this.payloadProcessor = payloadProcessor != null ? payloadProcessor : new LogPayloadProcessor(properties);
        this.recordBuilder = new LogRecordBuilder(properties, serviceName, requestInfoResolver, this.payloadProcessor);
    }

    /**
     * 记录系统日志
     *
     * @param module      业务模块
     * @param operation   操作类型
     * @param description 操作描述
     */
    @Override
    public void log(String module, String operation, String description) {
        LogRecord record = recordBuilder.buildBaseRecord(LogType.SYSTEM);
        record.setModule(module);
        record.setOperation(operation);
        record.setDescription(description);
        record.setSuccess(true);

        logPublisher.publish(record);
    }

    /**
     * 记录数据变更日志
     *
     * @param module      业务模块
     * @param operation   操作类型
     * @param targetType  操作对象类型
     * @param targetId    操作对象ID
     * @param beforeData  修改前数据
     * @param afterData   修改后数据
     */
    @Override
    public void logChange(String module, String operation, String targetType, String targetId,
                          Object beforeData, Object afterData) {
        logChange(module, operation, targetType, targetId, beforeData, afterData, null);
    }

    /**
     * 记录数据变更日志（带描述）
     *
     * @param module      业务模块
     * @param operation   操作类型
     * @param targetType  操作对象类型
     * @param targetId    操作对象ID
     * @param beforeData  修改前数据
     * @param afterData   修改后数据
     * @param description 操作描述
     */
    @Override
    public void logChange(String module, String operation, String targetType, String targetId,
                          Object beforeData, Object afterData, String description) {
        LogRecord record = recordBuilder.buildBaseRecord(LogType.SYSTEM);
        record.setModule(module);
        record.setOperation(operation);
        record.setTargetType(targetType);
        record.setTargetId(targetId);
        record.setDescription(description);
        record.setSuccess(true);

        if (beforeData != null) {
            record.setBeforeData(payloadProcessor.process(beforeData));
        }
        if (afterData != null) {
            record.setAfterData(payloadProcessor.process(afterData));
        }

        logPublisher.publish(record);
    }

    /**
     * 获取日志构建器
     *
     * @return 日志构建器
     */
    @Override
    public LogBuilder builder() {
        return new DefaultLogBuilder();
    }


    /**
     * 默认日志构建器实现
     */
    private class DefaultLogBuilder implements LogBuilder {

        /**
         * 日志记录
         */
        private final LogRecord record;

        /**
         * 是否显式设置了 success 标志
         */
        private boolean successSet = false;

        /**
         * 是否显式设置了 changeSummary
         */
        private boolean changeSummarySet = false;

        /**
         * 原始 beforeData（用于自动 diff）
         */
        private Object rawBeforeData;

        /**
         * 原始 afterData（用于自动 diff）
         */
        private Object rawAfterData;

        /**
         * 构造函数
         */
        public DefaultLogBuilder() {
            this.record = recordBuilder.buildBaseRecord(LogType.SYSTEM);
        }

        @Override
        public LogBuilder logType(LogType logType) {
            record.setLogType(logType);
            return this;
        }

        @Override
        public LogBuilder module(String module) {
            record.setModule(module);
            return this;
        }

        @Override
        public LogBuilder operation(String operation) {
            record.setOperation(operation);
            return this;
        }

        @Override
        public LogBuilder description(String description) {
            record.setDescription(description);
            return this;
        }

        @Override
        public LogBuilder targetType(String targetType) {
            record.setTargetType(targetType);
            return this;
        }

        @Override
        public LogBuilder targetId(String targetId) {
            record.setTargetId(targetId);
            return this;
        }

        @Override
        public LogBuilder beforeData(Object beforeData) {
            if (beforeData != null) {
                this.rawBeforeData = beforeData;
                record.setBeforeData(payloadProcessor.process(beforeData));
            }
            return this;
        }

        @Override
        public LogBuilder afterData(Object afterData) {
            if (afterData != null) {
                this.rawAfterData = afterData;
                record.setAfterData(payloadProcessor.process(afterData));
            }
            return this;
        }

        @Override
        public LogBuilder success(boolean success) {
            record.setSuccess(success);
            this.successSet = true;
            return this;
        }

        @Override
        public LogBuilder errorMessage(String errorMessage) {
            record.setErrorMessage(errorMessage);
            return this;
        }

        @Override
        public LogBuilder changeSummary(String changeSummary) {
            record.setChangeSummary(changeSummary);
            this.changeSummarySet = true;
            return this;
        }

        @Override
        public LogBuilder traceId(String traceId) {
            if (traceId != null && !traceId.isBlank()) {
                record.setTraceId(traceId);
            }
            return this;
        }

        @Override
        public LogBuilder parentSpanId(String parentSpanId) {
            record.setParentSpanId(parentSpanId);
            return this;
        }

        @Override
        public LogBuilder depth(int depth) {
            record.setDepth(depth);
            return this;
        }

        @Override
        public LogBuilder span(String className, String methodName) {
            record.setClassName(className);
            record.setMethodName(methodName);
            return this;
        }

        @Override
        public LogBuilder durationMs(long durationMs) {
            record.setDuration(durationMs);
            record.setActualDuration(durationMs);
            return this;
        }

        @Override
        public LogBuilder inputArgs(Object inputArgs) {
            if (inputArgs != null) {
                record.setInputArgs(payloadProcessor.process(inputArgs));
            }
            return this;
        }

        @Override
        public LogBuilder returnValue(Object returnValue) {
            if (returnValue != null) {
                record.setReturnValue(payloadProcessor.process(returnValue));
            }
            return this;
        }

        @Override
        public void record() {
            if (!successSet) {
                record.setSuccess(true);
            }
            // 链路型记录缺失 spanId 时就地生成（与切面 span 同格式）
            if (record.getLogType() == LogType.ACCESS && !StringUtils.hasText(record.getSpanId())) {
                record.setSpanId(InvocationContext.generateId());
            }
            // 自动生成变更摘要（当未手动设置且同时有 before/after 数据时）
            if (!changeSummarySet && rawBeforeData != null && rawAfterData != null) {
                String autoSummary = payloadProcessor.processChangeSummary(rawBeforeData, rawAfterData);
                if (autoSummary != null && !autoSummary.isEmpty()) {
                    record.setChangeSummary(autoSummary);
                }
            }
            // ACCESS 类型走链路车道（access_log 表），其余走业务车道
            if (record.getLogType() == LogType.ACCESS) {
                logPublisher.publishAccess(record);
            } else {
                logPublisher.publish(record);
            }
        }
    }
}
