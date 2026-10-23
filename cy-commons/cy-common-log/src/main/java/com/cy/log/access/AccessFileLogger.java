package com.cy.log.access;

import com.cy.log.util.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 调用链路日志文件输出器
 * <p>
 * 将调用链路 Span 按管道分隔格式写入 {@code log.access} logger，
 * 可通过 logback 配置将其输出到独立的 {@code access.log} 文件，
 * 方便用 {@code awk -F'|'} 等工具离线分析。
 * <p>
 * 日志格式（管道分隔）：
 * <pre>
 * version|access_time|traceId|threadName|depth|className|methodName|duration|actualDuration|clientIp|serverIp|resultCode|inputArgs|returnValue|
 * </pre>
 *
 * @author pcy
 * @date 2026/04/09
 */
public final class AccessFileLogger {

    private static final Logger ACCESS_LOGGER = LoggerFactory.getLogger("log.access");
    private static final Logger LOGGER = LoggerFactory.getLogger(AccessFileLogger.class);

    /**
     * 线程安全的时间格式化器（替代非线程安全的 SimpleDateFormat）
     */
    private static final DateTimeFormatter ACCESS_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final String VERSION = "2.0";

    private AccessFileLogger() {
    }

    /**
     * 将所有 span 输出到 log.access 日志文件
     *
     * @param spans    调用链路中的所有 span（按调用顺序）
     * @param traceId  链路追踪 ID
     * @param clientIp 客户端 IP
     */
    public static void log(List<AccessSpan> spans, String traceId, String clientIp) {
        if (spans == null || spans.isEmpty()) {
            return;
        }

        for (AccessSpan span : spans) {
            if (!span.isCompleted()) {
                continue;
            }
            try {
                String logStr = formatSpan(span, traceId, clientIp);
                ACCESS_LOGGER.info(logStr);
            } catch (Throwable e) {
                LOGGER.error("写入 access log 文件失败", e);
            }
        }
    }

    /**
     * 将单个 span 格式化为管道分隔字符串
     */
    private static String formatSpan(AccessSpan span, String traceId, String clientIp) {
        List<Object> fields = new ArrayList<>();
        fields.add(VERSION);                                           // version
        fields.add(ACCESS_TIME_FMT.format(Instant.ofEpochMilli(span.getStartTime()))); // access_time
        fields.add(traceId);                                           // traceId
        fields.add(Thread.currentThread().getName());                  // threadName
        fields.add(span.getDepth());                                   // deepness
        fields.add(span.getClassName());                               // className (module)
        fields.add(span.getMethodName());                              // methodName (service)
        fields.add(span.getDuration());                                // duration
        fields.add(span.getActualDuration());                          // actualDuration
        fields.add(clientIp != null ? clientIp : "");                  // clientIp
        fields.add(LogUtils.getServerIp());                            // serverIp
        fields.add(span.getResultCode());                              // resultCode
        fields.add(span.getInputArgs() != null ? span.getInputArgs() : "");   // inputArgs
        fields.add(span.getReturnValue() != null ? span.getReturnValue() : ""); // returnValue

        return fields.stream()
                .map(obj -> obj != null ? escapeField(String.valueOf(obj)) : "")
                .collect(Collectors.joining("|", "", "|"));
    }

    /**
     * 转义管道分隔符和换行符，防止格式错乱
     */
    private static String escapeField(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value
                .replace("|", "%7C")
                .replace("\r", "%0D")
                .replace("\n", "%0A");
    }
}
