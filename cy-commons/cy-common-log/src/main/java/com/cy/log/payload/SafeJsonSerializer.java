package com.cy.log.payload;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.cy.log.annotation.LogLimit;
import com.cy.log.annotation.LogMask;
import com.cy.log.annotation.LogSkip;
import com.cy.log.util.LogUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 安全 JSON 序列化器。
 * <p>
 * 与 {@link JSON#toJSONString(Object)} 相比，该序列化器在序列化时会施加以下硬约束，
 * 防止业务大对象（文件字节数组、超长集合、深嵌套对象、循环引用对象）触发 OOM：
 * </p>
 * <ul>
 *     <li><b>类型黑名单</b>：文件 / 流 / 二进制 / Servlet / Spring 内部对象自动跳过</li>
 *     <li><b>深度限制</b>：超过 {@link #MAX_DEPTH} 层只序列化为 {@code "<too-deep>"}</li>
 *     <li><b>集合元素限制</b>：超过 {@link #MAX_COLLECTION_SIZE} 元素自动截断并提示总数</li>
 *     <li><b>Map 条目限制</b>：同上</li>
 *     <li><b>字符串长度限制</b>：单个字符串字段超过 {@link #MAX_STRING_LENGTH} 自动截断</li>
 *     <li><b>{@code byte[]} 处理</b>：永远不会作为 base64 / 数字数组序列化，统一替换为 {@code "<binary size=X>"}</li>
 *     <li><b>注解兼容</b>：兼容 {@link LogSkip} / {@link LogMask} / {@link LogLimit} 字段注解</li>
 *     <li><b>失败兜底</b>：任何异常都降级返回 {@code "<unserializable>"}，绝不抛出到调用链</li>
 * </ul>
 * <p>
 * 这些约束完全在代码内置，不暴露成配置项，避免“配置迷宫”。
 * </p>
 *
 * @author pcy
 * @date 2026/04/29
 */
public final class SafeJsonSerializer {

    /**
     * 单字符串字段最大长度（超过会截断 + 省略号）。
     */
    private static final int MAX_STRING_LENGTH = 8 * 1024;

    /**
     * 集合 / 数组最大元素数量（超过会截断 + 标记总数）。
     */
    private static final int MAX_COLLECTION_SIZE = 50;

    /**
     * Map 最大条目数量。
     */
    private static final int MAX_MAP_SIZE = 50;

    /**
     * 单个 POJO 最大字段数量。
     */
    private static final int MAX_OBJECT_FIELDS = 50;

    /**
     * 对象嵌套最大深度（超过的对象会被替换为占位字符串）。
     */
    private static final int MAX_DEPTH = 6;

    /**
     * 二进制载荷占位字符串前缀（顶层 byte[] 与字段级 byte[] 都使用）。
     */
    private static final String BINARY_PLACEHOLDER_PREFIX = "<binary size=";

    /**
     * 嵌套深度超限占位字符串。
     */
    private static final String TOO_DEEP_PLACEHOLDER = "<too-deep>";

    /**
     * 循环引用占位字符串。
     */
    private static final String CYCLE_PLACEHOLDER = "<cycle>";

    /**
     * 反射读取字段失败占位字符串。
     */
    private static final String INACCESSIBLE_PLACEHOLDER = "<inaccessible>";

    /**
     * 集合 / 数组截断标记前缀。
     */
    private static final String TRUNCATED_TOTAL_PREFIX = "<truncated total=";

    /**
     * Map 截断时使用的特殊键。
     */
    private static final String MAP_TRUNCATED_KEY = "__truncated__";

    /**
     * 类型黑名单跳过占位字符串前缀。
     */
    private static final String SKIPPED_PLACEHOLDER_PREFIX = "<skipped:";

    /**
     * 序列化失败兜底字符串前缀。
     */
    private static final String UNSERIALIZABLE_PLACEHOLDER_PREFIX = "<unserializable:";

    /**
     * {@link #maskValue} 短字符串阈值：长度小于该值时整体替换为 {@link #MASK_FILL}。
     */
    private static final int MASK_MIN_LENGTH = 4;

    /**
     * {@link #maskValue} 保留首尾字符的最大数量。
     */
    private static final int MASK_KEEP_MAX_CHARS = 4;

    /**
     * 脱敏占位符（与 {@link LogUtils#SENSITIVE_PLACEHOLDER} 保持一致）。
     */
    private static final String MASK_FILL = LogUtils.SENSITIVE_PLACEHOLDER;

    /**
     * {@code @LogLimit} 注解字段截断时追加的省略号。
     * <p>
     * 与 {@link LogUtils#TRUNCATED_SUFFIX} 区分使用：{@code @LogLimit} 属于字段级展示策略
     * （如手机号显示前 7 位 + 省略号），使用更短的省略号避免污染字段语义。
     * </p>
     */
    private static final String LIMIT_ELLIPSIS = "...";

    /**
     * 不支持序列化的类型（直接跳过）。
     */
    private static final List<Class<?>> SKIP_TYPES = new ArrayList<>();

    /**
     * 类型 → 是否跳过 缓存。
     */
    private static final Map<Class<?>, Boolean> SKIP_TYPE_CACHE = new ConcurrentHashMap<>();

    /**
     * 类 → 可序列化字段缓存。
     */
    private static final Map<Class<?>, List<Field>> SERIALIZABLE_FIELD_CACHE = new ConcurrentHashMap<>();

    static {
        String[] skipClasses = {
                "jakarta.servlet.http.HttpServletRequest",
                "jakarta.servlet.http.HttpServletResponse",
                "jakarta.servlet.ServletRequest",
                "jakarta.servlet.ServletResponse",
                "jakarta.servlet.http.Part",
                "javax.servlet.http.HttpServletRequest",
                "javax.servlet.http.HttpServletResponse",
                "org.springframework.web.multipart.MultipartFile",
                "org.springframework.web.multipart.MultipartRequest",
                "org.springframework.core.io.Resource",
                "org.springframework.core.io.InputStreamSource",
                "org.springframework.ui.Model",
                "org.springframework.web.servlet.View",
                "org.springframework.web.servlet.ModelAndView",
                "org.springframework.context.ApplicationEvent",
                "org.springframework.web.servlet.mvc.method.annotation.SseEmitter",
                "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter",
                "org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody",
                "java.io.File",
                "java.io.InputStream",
                "java.io.OutputStream",
                "java.io.Reader",
                "java.io.Writer",
                "java.nio.ByteBuffer",
                "java.nio.channels.Channel",
                "io.netty.channel.Channel",
                "io.netty.buffer.ByteBuf",
                "reactor.core.publisher.Mono",
                "reactor.core.publisher.Flux",
                "org.reactivestreams.Publisher",
                "org.dom4j.Element"
        };
        for (String className : skipClasses) {
            try {
                SKIP_TYPES.add(Class.forName(className));
            } catch (ClassNotFoundException ignored) {
                // 类路径中不存在则忽略
            }
        }
    }

    private SafeJsonSerializer() {
    }

    /**
     * 判断指定类型是否应跳过序列化。
     */
    public static boolean shouldSkipType(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        return SKIP_TYPE_CACHE.computeIfAbsent(clazz, cls -> {
            for (Class<?> skipType : SKIP_TYPES) {
                if (skipType.isAssignableFrom(cls)) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * 安全序列化任意对象为 JSON 字符串（不应用字段注解 Filter）。
     * <p>
     * 对类型黑名单 / 大集合 / 深度 / 长字符串 / 二进制等场景施加硬约束，
     * 任何异常都降级为 {@code "<unserializable:Type>"}，永远不抛出到调用链。
     * </p>
     *
     * @param obj 待序列化对象
     * @return JSON 字符串，永不为 {@code null}
     */
    public static String serialize(Object obj) {
        return serializeInternal(obj, false);
    }

    /**
     * 安全序列化并附加 fastjson2 字段注解 Filter（{@link LogSkip}/{@link LogMask}/{@link LogLimit}）。
     */
    public static String serializeWithFilters(Object obj) {
        return serializeInternal(obj, true);
    }

    /**
     * 内部统一入口：所有硬约束 + 异常兜底逻辑都在这里集中。
     *
     * @param obj         待序列化对象
     * @param withFilters 是否启用字段注解 Filter
     * @return JSON 字符串，永不为 {@code null}
     */
    private static String serializeInternal(Object obj, boolean withFilters) {
        if (obj == null) {
            return "null";
        }
        if (shouldSkipType(obj.getClass())) {
            return "\"" + SKIPPED_PLACEHOLDER_PREFIX + obj.getClass().getSimpleName() + ">\"";
        }
        try {
            Object normalized = normalize(obj, 0, newVisitedSet(), withFilters);
            return JSON.toJSONString(normalized,
                    JSONWriter.Feature.ReferenceDetection,
                    JSONWriter.Feature.IgnoreNonFieldGetter);
        } catch (StackOverflowError | OutOfMemoryError fatal) {
            // 即便发生致命错误也不抛出，让日志组件本身保持静默降级
            return "\"" + UNSERIALIZABLE_PLACEHOLDER_PREFIX + fatal.getClass().getSimpleName() + ">\"";
        } catch (Throwable t) {
            return "\"" + UNSERIALIZABLE_PLACEHOLDER_PREFIX + t.getClass().getSimpleName() + ">\"";
        }
    }

    /**
     * 递归归一化对象，施加深度 / 集合大小 / 二进制等硬约束。
     * <p>
     * 返回的对象只包含 fastjson2 能直接序列化的安全结构（标量、List、Map、字符串）。
     * </p>
     */
    private static Object normalize(Object value, int depth, Set<Object> visited, boolean withFilters) {
        if (value == null) {
            return null;
        }
        if (depth > MAX_DEPTH) {
            return TOO_DEEP_PLACEHOLDER;
        }
        Class<?> type = value.getClass();
        if (shouldSkipType(type)) {
            return SKIPPED_PLACEHOLDER_PREFIX + type.getSimpleName() + ">";
        }
        if (value instanceof CharSequence cs) {
            String str = cs.toString();
            if (str.length() > MAX_STRING_LENGTH) {
                return str.substring(0, MAX_STRING_LENGTH) + LogUtils.TRUNCATED_SUFFIX;
            }
            return str;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character || type.isEnum()) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return BINARY_PLACEHOLDER_PREFIX + bytes.length + ">";
        }
        if (value instanceof Date
                || value instanceof TemporalAccessor
                || value instanceof UUID
                || value instanceof URI
                || value instanceof URL
                || value instanceof Class<?>) {
            return safeStringValue(value);
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(v -> normalize(v, depth + 1, visited, withFilters)).orElse(null);
        }
        if (isJdkValueType(type)) {
            return safeStringValue(value);
        }
        if (!visited.add(value)) {
            return CYCLE_PLACEHOLDER;
        }
        try {
            if (type.isArray()) {
                return normalizeArray(value, depth, visited, withFilters);
            }
            if (value instanceof Collection<?> collection) {
                return normalizeCollection(collection, depth, visited, withFilters);
            }
            if (value instanceof Map<?, ?> map) {
                return normalizeMap(map, depth, visited, withFilters);
            }
            return normalizeBean(value, depth, visited, withFilters);
        } finally {
            visited.remove(value);
        }
    }

    private static Object normalizeArray(Object array, int depth, Set<Object> visited, boolean withFilters) {
        int length = java.lang.reflect.Array.getLength(array);
        int actualSize = Math.min(length, MAX_COLLECTION_SIZE);
        List<Object> result = new ArrayList<>(actualSize + 1);
        for (int i = 0; i < actualSize; i++) {
            result.add(normalize(java.lang.reflect.Array.get(array, i), depth + 1, visited, withFilters));
        }
        if (length > MAX_COLLECTION_SIZE) {
            result.add(TRUNCATED_TOTAL_PREFIX + length + ">");
        }
        return result;
    }

    private static List<Object> normalizeCollection(Collection<?> collection, int depth, Set<Object> visited, boolean withFilters) {
        int total = collection.size();
        List<Object> result = new ArrayList<>(Math.min(total, MAX_COLLECTION_SIZE) + 1);
        int index = 0;
        for (Object element : collection) {
            if (index >= MAX_COLLECTION_SIZE) {
                result.add(TRUNCATED_TOTAL_PREFIX + total + ">");
                break;
            }
            result.add(normalize(element, depth + 1, visited, withFilters));
            index++;
        }
        return result;
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> map, int depth, Set<Object> visited, boolean withFilters) {
        Map<String, Object> result = new LinkedHashMap<>();
        int index = 0;
        int total = map.size();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (index >= MAX_MAP_SIZE) {
                result.put(MAP_TRUNCATED_KEY, "total=" + total);
                break;
            }
            String key = safeStringValue(entry.getKey());
            result.put(key, normalize(entry.getValue(), depth + 1, visited, withFilters));
            index++;
        }
        return result;
    }

    private static Map<String, Object> normalizeBean(Object bean, int depth, Set<Object> visited, boolean withFilters) {
        List<Field> fields = serializableFields(bean.getClass());
        Map<String, Object> result = new LinkedHashMap<>();
        int actualSize = Math.min(fields.size(), MAX_OBJECT_FIELDS);
        for (int i = 0; i < actualSize; i++) {
            Field field = fields.get(i);
            if (withFilters && field.isAnnotationPresent(LogSkip.class)) {
                continue;
            }
            Object rawValue = readField(field, bean);
            if (withFilters && field.isAnnotationPresent(LogMask.class)) {
                result.put(field.getName(), maskValue(rawValue));
                continue;
            }
            LogLimit limit = withFilters ? field.getAnnotation(LogLimit.class) : null;
            if (limit != null && limit.value() > 0) {
                Object normalizedValue = normalize(rawValue, depth + 1, visited, withFilters);
                result.put(field.getName(), limitValue(normalizedValue, limit.value()));
                continue;
            }
            result.put(field.getName(), normalize(rawValue, depth + 1, visited, withFilters));
        }
        if (fields.size() > MAX_OBJECT_FIELDS) {
            result.put(MAP_TRUNCATED_KEY, "total=" + fields.size());
        }
        return result;
    }

    private static Object readField(Field field, Object bean) {
        try {
            if (!field.canAccess(bean)) {
                field.setAccessible(true);
            }
            return field.get(bean);
        } catch (Throwable t) {
            return INACCESSIBLE_PLACEHOLDER;
        }
    }

    private static Set<Object> newVisitedSet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static boolean isJdkValueType(Class<?> type) {
        Package pkg = type.getPackage();
        return pkg != null
                && pkg.getName().startsWith("java.")
                && !type.isArray()
                && !Collection.class.isAssignableFrom(type)
                && !Map.class.isAssignableFrom(type);
    }

    private static List<Field> serializableFields(Class<?> clazz) {
        return SERIALIZABLE_FIELD_CACHE.computeIfAbsent(clazz, cls -> {
            List<Field> result = new ArrayList<>();
            collectSerializableFields(cls, result, new HashSet<>());
            return result;
        });
    }

    private static void collectSerializableFields(Class<?> clazz, List<Field> result, Set<String> visited) {
        if (clazz == null || clazz == Object.class) {
            return;
        }
        collectSerializableFields(clazz.getSuperclass(), result, visited);
        for (Field field : clazz.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
                continue;
            }
            if (visited.add(field.getName())) {
                result.add(field);
            }
        }
    }

    private static Object maskValue(Object value) {
        if (value == null) {
            return MASK_FILL;
        }
        if (value instanceof String str) {
            int len = str.length();
            if (len < MASK_MIN_LENGTH) {
                return MASK_FILL;
            }
            int idx = Math.min(len / MASK_MIN_LENGTH, MASK_KEEP_MAX_CHARS);
            return str.substring(0, idx) + MASK_FILL + str.substring(len - idx);
        }
        return MASK_FILL;
    }

    private static Object limitValue(Object value, int limit) {
        if (value == null) {
            return null;
        }
        String str = value instanceof String s ? s : safeStringValue(value);
        if (str.length() > limit) {
            return str.substring(0, limit) + LIMIT_ELLIPSIS;
        }
        return str;
    }

    private static String safeStringValue(Object value) {
        if (value == null) {
            return "null";
        }
        String str;
        try {
            str = String.valueOf(value);
        } catch (Throwable t) {
            str = UNSERIALIZABLE_PLACEHOLDER_PREFIX + t.getClass().getSimpleName() + ">";
        }
        if (str.length() > MAX_STRING_LENGTH) {
            return str.substring(0, MAX_STRING_LENGTH) + LogUtils.TRUNCATED_SUFFIX;
        }
        return str;
    }

    /**
     * 提供给外部访问的硬约束常量，便于策略层做二次判断。
     */
    public static int maxStringLength() {
        return MAX_STRING_LENGTH;
    }

    /**
     * 仅供测试 / 诊断使用：返回当前类型黑名单的拷贝。
     */
    static List<Class<?>> skipTypes() {
        return new ArrayList<>(SKIP_TYPES);
    }

}
