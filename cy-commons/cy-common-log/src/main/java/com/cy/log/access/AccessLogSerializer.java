package com.cy.log.access;

import com.cy.log.payload.FilePayloadDescriber;
import com.cy.log.payload.LogPayloadKind;
import com.cy.log.payload.SafeJsonSerializer;
import com.cy.log.util.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 调用链路日志序列化器。
 * <p>
 * 已统一收敛到 {@link SafeJsonSerializer}，所有方法入参 / 返回值的序列化策略与 API、SysLog 完全一致：
 * </p>
 * <ul>
 *     <li><b>类型黑名单</b>：HttpServletRequest / Stream / Resource / Reactor 等不可序列化对象自动跳过</li>
 *     <li><b>文件 / multipart / 二进制 / 流</b>：通过 {@link FilePayloadDescriber} 输出元信息（不读取内容）</li>
 *     <li><b>注解兼容</b>：{@code @LogSkip} / {@code @LogMask} / {@code @LogLimit} 字段在序列化阶段直接处理</li>
 *     <li><b>硬约束</b>：嵌套深度 / 集合元素数 / Map 条目数 / 字符串长度 / byte[] 摘要</li>
 *     <li><b>失败兜底</b>：任何异常返回 {@code <unserializable>}，绝不抛出</li>
 * </ul>
 *
 * @author pcy
 * @date 2026/04/09
 */
public final class AccessLogSerializer {

    private static final Logger log = LoggerFactory.getLogger(AccessLogSerializer.class);

    private AccessLogSerializer() {
    }

    /**
     * 序列化方法入参为 JSON 字符串。
     *
     * @param args      方法参数数组
     * @param charLimit 全局字符限制（-1 不限制）
     * @return JSON 字符串
     */
    public static String serializeArgs(Object[] args, int charLimit) {
        if (args == null || args.length == 0) {
            return "";
        }
        try {
            List<Object> normalized = new ArrayList<>(args.length);
            for (Object arg : args) {
                if (arg == null) {
                    continue;
                }
                Object descriptor = describeIfSpecial(arg);
                if (descriptor != null) {
                    normalized.add(descriptor);
                    continue;
                }
                if (SafeJsonSerializer.shouldSkipType(arg.getClass())) {
                    continue;
                }
                normalized.add(arg);
            }
            if (normalized.isEmpty()) {
                return "";
            }
            String json = normalized.size() == 1
                    ? safeSerialize(normalized.get(0))
                    : safeSerialize(normalized);
            return applyCharLimit(json, charLimit);
        } catch (Exception e) {
            log.debug("序列化入参失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 序列化方法返回值为 JSON 字符串。
     *
     * @param result    返回值
     * @param charLimit 全局字符限制（-1 不限制）
     * @return JSON 字符串
     */
    public static String serializeResult(Object result, int charLimit) {
        if (result == null) {
            return "";
        }
        try {
            Object descriptor = describeIfSpecial(result);
            if (descriptor != null) {
                return applyCharLimit(safeSerialize(descriptor), charLimit);
            }
            if (SafeJsonSerializer.shouldSkipType(result.getClass())) {
                return "";
            }
            String json = result instanceof String s ? s : safeSerialize(result);
            return applyCharLimit(json, charLimit);
        } catch (Exception e) {
            log.debug("序列化返回值失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 单次序列化封装：施加 {@code @LogSkip} / {@code @LogMask} / {@code @LogLimit} 字段处理与全局硬约束。
     */
    private static String safeSerialize(Object value) {
        return SafeJsonSerializer.serializeWithFilters(value);
    }

    /**
     * 文件 / multipart / 二进制 / 流 → 元信息描述对象。
     */
    private static Object describeIfSpecial(Object value) {
        if (FilePayloadDescriber.isMultiFile(value)) {
            return FilePayloadDescriber.toMetadataMap(LogPayloadKind.MULTIPART, value);
        }
        if (FilePayloadDescriber.isSingleFile(value)) {
            return FilePayloadDescriber.toMetadataMap(LogPayloadKind.FILE, value);
        }
        if (FilePayloadDescriber.isBinary(value)) {
            return FilePayloadDescriber.toMetadataMap(LogPayloadKind.BINARY, value);
        }
        if (FilePayloadDescriber.isStream(value)) {
            return FilePayloadDescriber.toMetadataMap(LogPayloadKind.STREAM, value);
        }
        return null;
    }

    /**
     * 应用全局字符限制（与全局截断后缀 {@link LogUtils#TRUNCATED_SUFFIX} 保持一致）。
     */
    private static String applyCharLimit(String str, int charLimit) {
        return LogUtils.truncate(str, charLimit);
    }
}
