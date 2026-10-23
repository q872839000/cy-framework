package com.cy.log.payload;

import com.cy.log.config.LogProperties;
import com.cy.log.core.LogRecord;
import com.cy.log.util.LogUtils;

import java.util.Objects;

/**
 * 日志记录发布前的最终兜底归一化器。
 * <p>
 * 即便所有日志入口都已经通过 {@link LogPayloadProcessor} 处理过，本类仍会作为
 * “发布闸门”再做一次最终保护，确保以下不变量永远成立：
 * </p>
 * <ul>
 *     <li>任何字符串字段长度 ≤ {@link LogProperties#getMaxBodyLength()}</li>
 *     <li>任何字符串字段字节数 ≤ {@link LogProperties#getMaxPayloadBytes()}</li>
 *     <li>{@code stackTrace} 字段单独限制在 {@link LogProperties#getTextSafeLimit()} 内</li>
 *     <li>错误消息字段不会因外部输入失控变长</li>
 * </ul>
 * <p>
 * 这样即便未来新增了某个日志入口忘记调用 {@link LogPayloadProcessor}，
 * 也不会把超长 / 危险字符串透传到 MQ 与数据库。
 * </p>
 *
 * @author pcy
 * @date 2026/04/29
 */
public final class LogRecordNormalizer {

    /**
     * 错误消息字段单独的硬上限（字符数）。
     */
    private static final int ERROR_MESSAGE_HARD_LIMIT = 4 * 1024;

    /**
     * 变更摘要字段单独的硬上限（字符数）。
     */
    private static final int CHANGE_SUMMARY_HARD_LIMIT = 512;

    /**
     * UTF-8 编码下单个 Java {@code char}（{@code BMP} 字符）的最坏字节占用。
     * <p>
     * 用于在不调用 {@code getBytes(UTF-8)} 的前提下保守估算字节数，
     * 保证按 {@code maxBytes / UTF8_WORST_BYTES_PER_CHAR} 字符截断后绝不超出字节上限。
     * </p>
     */
    private static final int UTF8_WORST_BYTES_PER_CHAR = 3;

    private final LogProperties properties;

    public LogRecordNormalizer(LogProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * 在发布前归一化日志记录的所有文本字段。
     */
    public void normalize(LogRecord record) {
        if (record == null) {
            return;
        }
        int max = effectiveMaxBodyLength();
        record.setRequestBody(clamp(record.getRequestBody(), max));
        record.setResponseBody(clamp(record.getResponseBody(), max));
        record.setInputArgs(clamp(record.getInputArgs(), max));
        record.setReturnValue(clamp(record.getReturnValue(), max));
        record.setBeforeData(clamp(record.getBeforeData(), max));
        record.setAfterData(clamp(record.getAfterData(), max));
        record.setRequestHeaders(clamp(record.getRequestHeaders(), max));
        record.setRequestParams(clamp(record.getRequestParams(), max));
        record.setDescription(clamp(record.getDescription(), max));
        record.setChangeSummary(clamp(record.getChangeSummary(), Math.min(max, CHANGE_SUMMARY_HARD_LIMIT)));
        record.setErrorMessage(clamp(record.getErrorMessage(), Math.min(max, ERROR_MESSAGE_HARD_LIMIT)));
        record.setStackTrace(clamp(record.getStackTrace(), Math.max(max, properties.getTextSafeLimit())));
    }

    private int effectiveMaxBodyLength() {
        return properties.getMaxBodyLength() > 0
                ? properties.getMaxBodyLength()
                : LogProperties.DEFAULT_MAX_BODY_LENGTH;
    }

    private int effectiveMaxPayloadBytes() {
        return properties.getMaxPayloadBytes() > 0
                ? properties.getMaxPayloadBytes()
                : LogProperties.DEFAULT_MAX_PAYLOAD_BYTES;
    }

    private String clamp(String value, int charLimit) {
        if (value == null) {
            return null;
        }
        int finalCharLimit = charLimit > 0 ? charLimit : effectiveMaxBodyLength();
        // 优先按字节硬限制收敛，避免超大字符串撑爆下游内存
        String byteClamped = clampByBytes(value, effectiveMaxPayloadBytes());
        return LogUtils.truncate(byteClamped, finalCharLimit);
    }

    /**
     * 按 UTF-8 字节硬上限收敛字符串。
     * <p>
     * 使用 {@link #UTF8_WORST_BYTES_PER_CHAR}（3 字节/char）的最坏估算，保证 BMP 范围内中英文混合
     * 文本截断后字节数不会超出 {@code maxBytes}；不调用 {@code getBytes()} 以避免分配大数组。
     * Surrogate pair 的 emoji（4 字节/char）依靠后续 {@link LogUtils#truncate} 字符上限再次兜底。
     * </p>
     */
    private static String clampByBytes(String value, int maxBytes) {
        if (value == null || maxBytes <= 0) {
            return value;
        }
        long approxBytes = (long) value.length() * UTF8_WORST_BYTES_PER_CHAR;
        if (approxBytes <= maxBytes) {
            return value;
        }
        int maxChars = Math.max(0, maxBytes / UTF8_WORST_BYTES_PER_CHAR);
        return LogUtils.truncate(value, maxChars);
    }
}
