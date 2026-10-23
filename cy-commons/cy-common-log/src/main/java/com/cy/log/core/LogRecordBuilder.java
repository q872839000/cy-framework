package com.cy.log.core;

import com.cy.core.context.InvocationContext;
import com.cy.core.context.InvocationContextAccessor;
import com.cy.log.config.LogProperties;
import com.cy.log.payload.LogPayloadProcessor;
import com.cy.log.request.RequestInfo;
import com.cy.log.request.RequestInfoResolver;
import com.cy.orm.utils.IdGeneratedUtil;
import com.cy.log.util.LogUtils;
import org.springframework.util.StringUtils;

/**
 * 日志记录构建器，统一日志记录的构建逻辑。
 * <p>
 * 消除 SysLogAspect、ApiLogAspect、LogServiceImpl 中的重复代码。
 *
 * @author pcy
 * @date 2026/04/16
 */
public class LogRecordBuilder {

    private final String serviceName;
    private final RequestInfoResolver requestInfoResolver;
    private final LogPayloadProcessor payloadProcessor;

    public LogRecordBuilder(LogProperties properties, String serviceName, RequestInfoResolver requestInfoResolver) {
        this(properties, serviceName, requestInfoResolver, new LogPayloadProcessor(properties));
    }

    public LogRecordBuilder(LogProperties properties, String serviceName,
                            RequestInfoResolver requestInfoResolver,
                            LogPayloadProcessor payloadProcessor) {
        this.serviceName = serviceName;
        this.requestInfoResolver = requestInfoResolver;
        this.payloadProcessor = payloadProcessor != null ? payloadProcessor : new LogPayloadProcessor(properties);
    }

    /**
     * 创建基础日志记录，填充通用字段。
     *
     * @param logType 日志类型
     * @return 日志记录
     */
    public LogRecord buildBaseRecord(LogType logType) {
        LogRecord record = new LogRecord();
        InvocationContext context = InvocationContextAccessor.current();

        record.setId(IdGeneratedUtil.generateId());
        record.setTraceId(LogUtils.resolveTraceId(context));
        record.setLogType(logType);
        record.setServiceName(serviceName);
        record.setServerIp(LogUtils.getServerIp());

        if (context != null) {
            record.setSpanId(context.getSpanId());
            record.setParentSpanId(context.getParentSpanId());
            record.setSource(context.getSource());
            record.setClientIp(context.getClientIp());
            record.setRequestUri(context.getRequestUri());
            record.setRequestMethod(context.getRequestMethod());
        }

        supplementRequestInfo(record);
        LogUtils.applyInvocationUser(record, context);

        long now = System.currentTimeMillis();
        record.setCreateTime(now);
        record.setTablePartition(LogUtils.getPartition(now));

        return record;
    }

    /**
     * 从 RequestInfoResolver 补充请求信息（兜底机制）。
     */
    private void supplementRequestInfo(LogRecord record) {
        if (requestInfoResolver == null) {
            return;
        }

        boolean needSupplement = !StringUtils.hasText(record.getRequestUri())
                || !StringUtils.hasText(record.getRequestMethod())
                || !StringUtils.hasText(record.getClientIp());

        if (!needSupplement) {
            return;
        }

        try {
            RequestInfo reqInfo = requestInfoResolver.resolve();
            if (reqInfo == null) {
                return;
            }

            if (!StringUtils.hasText(record.getRequestUri())) {
                record.setRequestUri(reqInfo.getRequestUri());
            }
            if (!StringUtils.hasText(record.getRequestMethod())) {
                record.setRequestMethod(reqInfo.getRequestMethod());
            }
            if (!StringUtils.hasText(record.getClientIp())) {
                record.setClientIp(reqInfo.getClientIp());
            }
        } catch (Exception e) {
            // 非 HTTP 请求环境（如定时任务、MQ 消费）正常忽略
        }
    }

    /**
     * 序列化对象为 JSON 并应用脱敏和截断。
     * <p>
     * <b>本方法已统一委托 {@link LogPayloadProcessor#process(Object)}</b>，
     * 自动支持文件 / multipart / 二进制 / 流的元信息记录、深度 / 集合 / 字符串硬约束、
     * 敏感字段脱敏与最终展示截断。
     * </p>
     *
     * @param obj 对象
     * @return 处理后的 JSON 字符串
     */
    public String toJsonWithSanitize(Object obj) {
        return payloadProcessor.process(obj);
    }
}
