package com.cy.log.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.cy.core.context.InvocationContext;
import com.cy.core.utils.ClientIpResolver;
import com.cy.log.config.LogProperties;
import com.cy.log.core.LogRecord;
import com.cy.log.payload.SafeJsonSerializer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 日志工具类
 * <p>
 * 提供日志记录相关的工具方法，包括：
 * <ul>
 *     <li>获取客户端真实IP</li>
 *     <li>获取服务器IP</li>
 *     <li>敏感数据脱敏</li>
 *     <li>异常堆栈转换</li>
 *     <li>分表标识生成</li>
 * </ul>
 *
 * @author pcy
 * @date 2026/01/29
 */
public final class LogUtils {

    /**
     * 未知IP
     */
    private static final String UNKNOWN = "unknown";

    /**
     * 本地IP
     */
    private static final String LOCALHOST = "127.0.0.1";

    /**
     * 字符串截断后追加的统一后缀。
     * <p>
     * 整个 {@code cy-common-log} 模块统一使用该后缀，便于下游日志检索定位被截断字段。
     * </p>
     */
    public static final String TRUNCATED_SUFFIX = "...(truncated)";

    /**
     * 敏感字段脱敏占位符（用于 JSON 字段值整体替换）。
     */
    public static final String SENSITIVE_PLACEHOLDER = "******";

    /**
     * 变更摘要中敏感字段的脱敏占位符。
     * <p>
     * 与 {@link #SENSITIVE_PLACEHOLDER} 区分使用：变更摘要属于人可读短文本，
     * 使用三个星号既能视觉提示已脱敏、又不会让摘要过长。
     * </p>
     */
    public static final String DIFF_MASK_PLACEHOLDER = "***";

    /**
     * 变更摘要（{@link #diff(Object, Object, List)}）的默认最大字符数。
     */
    private static final int DEFAULT_DIFF_MAX_LENGTH = 512;

    /**
     * 分表标识日期格式
     */
    private static final DateTimeFormatter PARTITION_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * 关键请求头列表
     */
    private static final List<String> KEY_HEADERS = List.of(
            "Content-Type",
            "User-Agent",
            "Accept",
            "Accept-Language",
            "Referer",
            "Origin"
    );

    /**
     * 缓存的服务器IP（volatile 保证多线程可见性）
     */
    private static volatile String cachedServerIp;

    /**
     * 合法表名正则：仅允许字母、数字、下划线
     */
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

    /**
     * Spring Ant 风格路径匹配器
     */
    private static final org.springframework.util.AntPathMatcher ANT_PATH_MATCHER = new org.springframework.util.AntPathMatcher();

    /**
     * 私有构造函数，防止实例化
     */
    private LogUtils() {
    }

    /**
     * 获取客户端真实IP地址
     * <p>
     * 支持从代理头（X-Forwarded-For、X-Real-IP等）中获取真实IP
     *
     * @param request HTTP请求对象
     * @return 客户端IP地址
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        return ClientIpResolver.resolve(request::getHeader, request.getRemoteAddr());
    }

    /**
     * 判断IP是否有效
     *
     * @param ip IP地址
     * @return 如果IP有效返回true
     */
    private static boolean isValidIp(String ip) {
        return StringUtils.hasText(ip) && !UNKNOWN.equalsIgnoreCase(ip);
    }

    /**
     * 获取服务器IP地址
     * <p>
     * 首次调用时获取并缓存，后续调用直接返回缓存值（双重检查锁定）
     *
     * @return 服务器IP地址
     */
    public static String getServerIp() {
        String ip = cachedServerIp;
        if (ip != null) {
            return ip;
        }
        synchronized (LogUtils.class) {
            ip = cachedServerIp;
            if (ip != null) {
                return ip;
            }
            try {
                ip = InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException e) {
                ip = LOCALHOST;
            }
            cachedServerIp = ip;
            return ip;
        }
    }

    public static String resolveTraceId(InvocationContext context) {
        return context != null && StringUtils.hasText(context.getTraceId())
                ? context.getTraceId()
                : InvocationContext.generateId();
    }

    public static void applyInvocationUser(LogRecord record, InvocationContext context) {
        if (record == null || context == null || context.getUserId() == null) {
            return;
        }
        record.setUserId(context.getUserId());
        record.setUsername(context.getUsername());
        record.setNickname(context.getNickname());
    }

    /**
     * 提取关键请求头
     *
     * @param request HTTP请求对象
     * @return 请求头JSON字符串
     */
    public static String extractHeaders(HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        Map<String, String> headers = new HashMap<>();
        for (String headerName : KEY_HEADERS) {
            String headerValue = request.getHeader(headerName);
            if (StringUtils.hasText(headerValue)) {
                headers.put(headerName, headerValue);
            }
        }

        return headers.isEmpty() ? null : JSON.toJSONString(headers);
    }

    /**
     * 提取所有请求参数
     *
     * @param request HTTP请求对象
     * @return 请求参数JSON字符串
     */
    public static String extractParams(HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        Map<String, String[]> parameterMap = request.getParameterMap();
        if (parameterMap.isEmpty()) {
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        parameterMap.forEach((key, values) -> {
            if (values.length == 1) {
                params.put(key, values[0]);
            } else {
                params.put(key, values);
            }
        });

        return JSON.toJSONString(params);
    }

    /**
     * 敏感数据脱敏
     * <p>
     * 将JSON字符串中的敏感字段值替换为 ******
     *
     * @param json            JSON字符串
     * @param sensitiveFields 敏感字段列表
     * @return 脱敏后的JSON字符串
     */
    public static String desensitize(String json, List<String> sensitiveFields) {
        if (!StringUtils.hasText(json) || sensitiveFields == null || sensitiveFields.isEmpty()) {
            return json;
        }

        try {
            JSONObject jsonObject = JSON.parseObject(json);
            if (jsonObject == null) {
                return json;
            }

            // 递归处理敏感字段
            desensitizeObject(jsonObject, sensitiveFields);
            return jsonObject.toJSONString();
        } catch (Exception e) {
            // JSON解析失败，返回原字符串
            return json;
        }
    }

    /**
     * 递归脱敏JSON对象
     *
     * @param jsonObject      JSON对象
     * @param sensitiveFields 敏感字段列表
     */
    private static void desensitizeObject(JSONObject jsonObject, List<String> sensitiveFields) {
        for (String key : jsonObject.keySet()) {
            // 检查是否为敏感字段（忽略大小写）
            boolean isSensitive = sensitiveFields.stream()
                    .anyMatch(field -> field.equalsIgnoreCase(key));

            if (isSensitive) {
                // 敏感字段脱敏
                jsonObject.put(key, SENSITIVE_PLACEHOLDER);
            } else {
                // 递归处理嵌套对象和数组
                Object value = jsonObject.get(key);
                if (value instanceof JSONObject nestedObj) {
                    desensitizeObject(nestedObj, sensitiveFields);
                } else if (value instanceof JSONArray nestedArr) {
                    desensitizeArray(nestedArr, sensitiveFields);
                }
            }
        }
    }

    /**
     * 递归脱敏JSON数组中的对象元素
     *
     * @param jsonArray       JSON数组
     * @param sensitiveFields 敏感字段列表
     */
    private static void desensitizeArray(JSONArray jsonArray, List<String> sensitiveFields) {
        for (int i = 0; i < jsonArray.size(); i++) {
            Object element = jsonArray.get(i);
            if (element instanceof JSONObject nestedObj) {
                desensitizeObject(nestedObj, sensitiveFields);
            } else if (element instanceof JSONArray nestedArr) {
                desensitizeArray(nestedArr, sensitiveFields);
            }
        }
    }

    /**
     * 截断字符串
     * <p>
     * 如果字符串长度超过最大长度，则截断并添加省略标记
     *
     * @param str       原字符串
     * @param maxLength 最大长度
     * @return 截断后的字符串
     */
    public static String truncate(String str, int maxLength) {
        if (str == null || maxLength <= 0 || str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + TRUNCATED_SUFFIX;
    }

    /**
     * 将异常堆栈转换为字符串
     *
     * @param throwable 异常对象
     * @return 堆栈字符串
     */
    public static String getStackTrace(Throwable throwable) {
        if (throwable == null) {
            return null;
        }

        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            throwable.printStackTrace(pw);
        }
        return sw.toString();
    }

    /**
     * 获取当前月份的分表标识
     * <p>
     * 格式：yyyyMM，如：202601
     *
     * @return 分表标识
     */
    public static String getCurrentPartition() {
        return LocalDateTime.now().format(PARTITION_FORMATTER);
    }

    /**
     * 根据时间戳获取分表标识
     *
     * @param timestamp 毫秒时间戳
     * @return 分表标识
     */
    public static String getPartition(long timestamp) {
        LocalDateTime dateTime = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamp),
                java.time.ZoneId.systemDefault()
        );
        return dateTime.format(PARTITION_FORMATTER);
    }

    /**
     * 对象转 JSON 字符串。
     * <p>
     * <b>已收敛到 {@link SafeJsonSerializer}</b>：内部启用类型黑名单、深度 / 集合 / 字符串硬约束，
     * 防止文件 / 大集合 / 深嵌套对象触发 OOM。任何异常都会降级为 {@code <unserializable>} 而不抛出。
     * </p>
     * <p>
     * 推荐改用 {@code com.cy.log.payload.LogPayloadProcessor#process(Object)}，那个方法还会附加
     * 文件元信息识别、敏感字段脱敏、最终展示截断等统一治理逻辑。
     * </p>
     *
     * @param obj 对象
     * @return JSON 字符串，永不抛出异常；{@code obj == null} 时返回 {@code null}
     */
    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        return SafeJsonSerializer.serializeWithFilters(obj);
    }

    /**
     * 检查URL是否匹配排除模式
     *
     * @param uri             请求URI
     * @param excludePatterns 排除模式列表（Ant风格）
     * @return 如果匹配返回true
     */
    public static boolean isExcluded(String uri, List<String> excludePatterns) {
        if (!StringUtils.hasText(uri) || excludePatterns == null || excludePatterns.isEmpty()) {
            return false;
        }

        for (String pattern : excludePatterns) {
            if (matchAntPattern(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对比两个对象的 JSON 表示，生成简短的变更摘要。
     * <p>
     * 例如输出：{@code "nickname: 张三→李四, status: 1→0"}。
     * 敏感字段的值会被脱敏为 {@code ***}。
     * 最长 {@value DEFAULT_DIFF_MAX_LENGTH} 字符，超出部分截断并追加 {@link #TRUNCATED_SUFFIX}。
     *
     * @param before          修改前数据
     * @param after           修改后数据
     * @param sensitiveFields 敏感字段列表
     * @return 变更摘要字符串，无差异时返回空字符串
     */
    public static String diff(Object before, Object after, List<String> sensitiveFields) {
        return diff(before, after, sensitiveFields, DEFAULT_DIFF_MAX_LENGTH);
    }

    /**
     * 对比两个对象的 JSON 表示，生成简短的变更摘要（自定义截断长度）。
     *
     * @param before          修改前数据
     * @param after           修改后数据
     * @param sensitiveFields 敏感字段列表
     * @param maxLength       结果最大字符数（{@code <=0} 表示不截断）
     * @return 变更摘要字符串，无差异时返回空字符串
     */
    public static String diff(Object before, Object after, List<String> sensitiveFields, int maxLength) {
        try {
            JSONObject beforeJson = toJsonObject(before);
            JSONObject afterJson = toJsonObject(after);
            if (beforeJson == null || afterJson == null) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            for (String key : afterJson.keySet()) {
                Object oldVal = beforeJson.get(key);
                Object newVal = afterJson.get(key);
                if (!java.util.Objects.equals(oldVal, newVal)) {
                    appendDiff(sb, key, oldVal, newVal, sensitiveFields);
                }
            }
            for (String key : beforeJson.keySet()) {
                if (!afterJson.containsKey(key)) {
                    appendDiff(sb, key, beforeJson.get(key), null, sensitiveFields);
                }
            }
            return truncate(sb.toString(), maxLength);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 将一对 {@code (key, oldValue, newValue)} 追加到 diff 字符串构建器。
     * <p>
     * 供日志组件内部（{@code com.cy.log.payload.LogPayloadProcessor} 等）复用，避免重复实现。
     * 业务代码请直接使用 {@link #diff(Object, Object, List)}。
     * </p>
     */
    public static void appendDiff(StringBuilder sb, String key, Object oldVal, Object newVal,
                                  List<String> sensitiveFields) {
        if (!sb.isEmpty()) {
            sb.append(", ");
        }
        boolean sensitive = sensitiveFields != null && sensitiveFields.stream()
                .anyMatch(f -> f.equalsIgnoreCase(key));
        String oldStr = sensitive ? DIFF_MASK_PLACEHOLDER : String.valueOf(oldVal);
        String newStr = sensitive ? DIFF_MASK_PLACEHOLDER : String.valueOf(newVal);
        sb.append(key).append(": ").append(oldStr).append("→").append(newStr);
    }

    /**
     * 将对象转为 JSONObject，支持 JSONObject / JSON 字符串 / 普通 POJO。
     * <p>
     * 通过 {@link SafeJsonSerializer} 进行中间序列化，避免大对象 OOM。
     * 供日志组件内部（{@code com.cy.log.payload.LogPayloadProcessor} 等）复用。
     * </p>
     */
    public static JSONObject toJsonObject(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof JSONObject jo) {
            return jo;
        }
        if (obj instanceof String str) {
            try {
                return JSON.parseObject(str);
            } catch (Exception e) {
                return null;
            }
        }
        try {
            String json = SafeJsonSerializer.serialize(obj);
            return JSON.parseObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 校验表名是否合法（防止 SQL 注入）
     * <p>
     * 仅允许字母、数字、下划线
     *
     * @param tableName 表名
     * @throws IllegalArgumentException 如果表名不合法
     */
    public static void validateTableName(String tableName) {
        if (tableName == null || !TABLE_NAME_PATTERN.matcher(tableName).matches()) {
            throw new IllegalArgumentException("非法表名: " + tableName);
        }
    }

    /**
     * Ant风格路径匹配（委托 Spring AntPathMatcher）
     *
     * @param pattern 模式
     * @param path    路径
     * @return 如果匹配返回true
     */
    private static boolean matchAntPattern(String pattern, String path) {
        return ANT_PATH_MATCHER.match(pattern, path);
    }
}
