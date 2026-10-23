package com.cy.log.payload;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.cy.log.config.LogProperties;
import com.cy.log.util.LogUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 日志载荷统一处理器。
 * <p>
 * 这是 {@code cy-common-log} 中所有日志类型（API、SysLog、Access、手动 LogService、响应式）唯一的
 * “对象 → 安全文本”入口。任何日志入口都应当调用本类的方法生成最终写入字段的字符串，
 * 再交给 {@link com.cy.log.core.LogPublisher} 发布。
 * </p>
 * <p>
 * 处理顺序如下：
 * </p>
 * <ol>
 *     <li>类型识别：{@link FilePayloadDescriber} 判断是文件 / multipart / 二进制 / 流</li>
 *     <li>类型黑名单：{@link SafeJsonSerializer#shouldSkipType(Class)} 跳过 Servlet/Spring 内部对象</li>
 *     <li>结构化输出：文件类输出 manifest，二进制 / 流输出元信息</li>
 *     <li>安全序列化：其余对象通过 {@link SafeJsonSerializer} 施加深度 / 集合 / 字符串硬约束</li>
 *     <li>预览采集：超过 {@link LogProperties#getMaxPayloadBytes()} 字节自动转 PREVIEW + truncated</li>
 *     <li>脱敏：按 {@link LogProperties#getSensitiveFields()} 对 JSON 字段值脱敏</li>
 *     <li>展示截断：按 {@link LogProperties#getMaxBodyLength()} 截断到最终展示长度</li>
 * </ol>
 * <p>
 * 任何步骤都不会抛出异常，最坏情况下返回 {@code "<unserializable>"} 兜底文本。
 * </p>
 *
 * @author pcy
 * @date 2026/04/29
 */
public final class LogPayloadProcessor {

    /**
     * 预览采集模式下输出的载荷类型标识。
     */
    private static final String PREVIEW_KIND_TEXT = "TEXT";

    /**
     * 序列化失败时的兜底 JSON 模板。
     */
    private static final String FALLBACK_JSON_TEMPLATE =
            "{\"capture\":\"FALLBACK\",\"reason\":\"%s\"}";

    private final LogProperties properties;

    public LogPayloadProcessor(LogProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    // ==================== 通用对象处理 ====================

    /**
     * 安全处理任意对象，返回最终用于日志字段的字符串。
     */
    public String process(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String json = describeIfSpecial(value);
            if (json == null) {
                json = SafeJsonSerializer.serializeWithFilters(value);
            }
            return applyFinalSanitization(json);
        } catch (Throwable t) {
            return applyFinalSanitization(fallback(t));
        }
    }

    /**
     * 处理 HTTP 请求体。
     *
     * @param body            已经被有界缓存的字节内容（可能为 {@code null} 表示未捕获）
     * @param contentType     HTTP {@code Content-Type}（可为 {@code null}）
     * @param contentLength   请求体的字节数：调用方在 {@code BoundedContentCachingRequestWrapper} 已截断时
     *                        应传入观测到的实际字节数（{@code observedBytes}），未截断时传入 HTTP
     *                        {@code Content-Length} 头部值；{@code -1} 表示未知（如 chunked 且未触发截断）
     * @param multipartValue  当请求是 multipart 时，从 controller 参数中识别出的 multipart 对象（可为 {@code null}）
     * @return 安全文本，永远不会因大请求体触发 OOM
     */
    public String processHttpRequestBody(byte[] body, String contentType, long contentLength, Object multipartValue) {
        try {
            // 优先级 1：multipart → 文件清单
            if (multipartValue != null && FilePayloadDescriber.isMultiFile(multipartValue)) {
                Map<String, Object> map = FilePayloadDescriber.toMetadataMap(LogPayloadKind.MULTIPART, multipartValue);
                map.put("contentType", contentType);
                if (contentLength >= 0) {
                    map.put("contentLength", contentLength);
                }
                return applyFinalSanitization(JSON.toJSONString(map));
            }
            // 优先级 2：单文件 → 文件 summary
            if (multipartValue != null && FilePayloadDescriber.isSingleFile(multipartValue)) {
                Map<String, Object> map = FilePayloadDescriber.toMetadataMap(LogPayloadKind.FILE, multipartValue);
                map.put("contentType", contentType);
                if (contentLength >= 0) {
                    map.put("contentLength", contentLength);
                }
                return applyFinalSanitization(JSON.toJSONString(map));
            }
            // 优先级 3：multipart 类型但 controller 没拿到对象 → 通用 multipart 摘要
            if (ContentTypes.isMultipart(contentType)) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("kind", LogPayloadKind.MULTIPART.name());
                map.put("capture", LogCaptureMode.METADATA_ONLY.name());
                map.put("contentType", contentType);
                if (contentLength >= 0) {
                    map.put("contentLength", contentLength);
                }
                return applyFinalSanitization(JSON.toJSONString(map));
            }
            // 优先级 4：二进制 Content-Type → 摘要
            if (ContentTypes.isBinary(contentType)) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("kind", LogPayloadKind.BINARY.name());
                map.put("capture", LogCaptureMode.METADATA_ONLY.name());
                map.put("contentType", contentType);
                if (contentLength >= 0) {
                    map.put("contentLength", contentLength);
                }
                if (body != null) {
                    map.put("capturedBytes", body.length);
                }
                return applyFinalSanitization(JSON.toJSONString(map));
            }
            // 优先级 5：文本 body
            if (body == null || body.length == 0) {
                return null;
            }
            String text = new String(body, StandardCharsets.UTF_8);
            int maxPayloadBytes = effectiveMaxPayloadBytes();
            // body 已经被有界缓存：当 contentLength（observedBytes 或 HTTP Content-Length）
            // 严格大于 maxPayloadBytes 时即认为发生了截断
            boolean truncated = contentLength > 0 && contentLength > maxPayloadBytes;
            if (text.length() > SafeJsonSerializer.maxStringLength()) {
                truncated = true;
                text = text.substring(0, SafeJsonSerializer.maxStringLength());
            }
            if (truncated) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("kind", PREVIEW_KIND_TEXT);
                map.put("capture", LogCaptureMode.PREVIEW.name());
                map.put("contentType", contentType);
                if (contentLength >= 0) {
                    map.put("contentLength", contentLength);
                }
                map.put("capturedBytes", body.length);
                map.put("truncated", true);
                map.put("preview", text);
                return applyFinalSanitization(JSON.toJSONString(map));
            }
            // 直接返回文本，并按通用流程脱敏 + 截断
            return applyFinalSanitization(text);
        } catch (Throwable t) {
            return applyFinalSanitization(fallback(t));
        }
    }

    /**
     * 处理 HTTP 响应体（通常是 controller 返回的对象）。
     */
    public String processHttpResponseBody(Object result) {
        return process(result);
    }

    /**
     * 生成变更摘要，例如 {@code "nickname: 张三→李四, status: 1→0"}。
     * <p>
     * 复用 {@link LogUtils#toJsonObject(Object)} / {@link LogUtils#appendDiff} 的实现，
     * 避免与 {@code LogUtils.diff} 重复一份逻辑；变更摘要本身按 {@link LogProperties#getMaxBodyLength()} 截断。
     * </p>
     */
    public String processChangeSummary(Object before, Object after) {
        if (before == null && after == null) {
            return null;
        }
        try {
            JSONObject beforeJson = LogUtils.toJsonObject(before);
            JSONObject afterJson = LogUtils.toJsonObject(after);
            if (beforeJson == null || afterJson == null) {
                return null;
            }
            List<String> sensitive = properties.getSensitiveFields();
            StringBuilder sb = new StringBuilder();
            for (String key : afterJson.keySet()) {
                Object oldVal = beforeJson.get(key);
                Object newVal = afterJson.get(key);
                if (!Objects.equals(oldVal, newVal)) {
                    LogUtils.appendDiff(sb, key, oldVal, newVal, sensitive);
                }
            }
            for (String key : beforeJson.keySet()) {
                if (!afterJson.containsKey(key)) {
                    LogUtils.appendDiff(sb, key, beforeJson.get(key), null, sensitive);
                }
            }
            return sb.isEmpty() ? null : truncate(sb.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 应用敏感字段脱敏。
     * <p>
     * 委托 {@link LogUtils#desensitize(String, List)}，避免重复实现脱敏算法。
     * </p>
     */
    public String desensitize(String json) {
        return LogUtils.desensitize(json, properties.getSensitiveFields());
    }

    /**
     * 按 {@link LogProperties#getMaxBodyLength()} 截断字符串。
     * <p>
     * 委托 {@link LogUtils#truncate(String, int)}，全局保持一致的截断后缀 {@link LogUtils#TRUNCATED_SUFFIX}。
     * </p>
     */
    public String truncate(String value) {
        return LogUtils.truncate(value, properties.getMaxBodyLength());
    }

    // ==================== 内部实现 ====================

    /**
     * 如果对象属于文件 / multipart / 二进制 / 流等特殊载荷，返回结构化 JSON；否则返回 {@code null}。
     */
    private String describeIfSpecial(Object value) {
        if (FilePayloadDescriber.isMultiFile(value)) {
            return JSON.toJSONString(FilePayloadDescriber.toMetadataMap(LogPayloadKind.MULTIPART, value));
        }
        if (FilePayloadDescriber.isSingleFile(value)) {
            return JSON.toJSONString(FilePayloadDescriber.toMetadataMap(LogPayloadKind.FILE, value));
        }
        if (FilePayloadDescriber.isBinary(value)) {
            return JSON.toJSONString(FilePayloadDescriber.toMetadataMap(LogPayloadKind.BINARY, value));
        }
        if (FilePayloadDescriber.isStream(value)) {
            return JSON.toJSONString(FilePayloadDescriber.toMetadataMap(LogPayloadKind.STREAM, value));
        }
        if (value instanceof CharSequence cs) {
            return finalizeText(cs.toString());
        }
        return null;
    }

    /**
     * 文本预览模式：超过 maxPayloadBytes 时只保留预览片段。
     */
    private String finalizeText(String text) {
        if (text == null) {
            return "null";
        }
        int maxBytes = effectiveMaxPayloadBytes();
        if (text.length() > maxBytes) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", PREVIEW_KIND_TEXT);
            map.put("capture", LogCaptureMode.PREVIEW.name());
            map.put("contentLength", text.length());
            map.put("truncated", true);
            map.put("preview", text.substring(0, Math.min(text.length(), SafeJsonSerializer.maxStringLength())));
            return JSON.toJSONString(map);
        }
        // 安全短文本：直接返回原始字符串（不做 JSON 包裹，便于已经是 JSON 的串保持原状）
        return text;
    }

    /**
     * 应用脱敏 + 最终展示截断（公共后处理流水线）。
     * <p>
     * 命名为 {@code applyFinalSanitization} 而非 {@code finalize}，避免与 {@link Object#finalize()} 同名误导读者。
     * </p>
     */
    private String applyFinalSanitization(String json) {
        if (json == null) {
            return null;
        }
        return truncate(desensitize(json));
    }

    /**
     * 当前生效的 {@link LogProperties#getMaxPayloadBytes()}（拒绝非法值）。
     */
    private int effectiveMaxPayloadBytes() {
        int value = properties.getMaxPayloadBytes();
        return value > 0 ? value : LogProperties.DEFAULT_MAX_PAYLOAD_BYTES;
    }

    /**
     * 序列化兜底文本：使用预定义模板，避免在异常路径中再次触发字符串拼接异常。
     */
    private static String fallback(Throwable t) {
        return String.format(FALLBACK_JSON_TEMPLATE, escape(t.getClass().getSimpleName()));
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\"", "\\\"");
    }

}
