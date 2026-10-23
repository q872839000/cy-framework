package com.cy.mq.runtime;

import com.cy.mq.core.MqMetadata;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * MQ 链路追踪上下文工具。
 * <p>
 * 集中处理 MQ 发布与消费两端的 trace 上下文捕获 / 还原，覆盖 MDC 与
 * 可选的 {@code com.cy.core.context.InvocationContext}（仅当 cy-common-core
 * 在类路径时生效，运行期反射访问，不强依赖）。
 * </p>
 * <p>
 * 设计要点：
 * </p>
 * <ul>
 *     <li>发布时优先从 InvocationContext 读 traceId / spanId / parentSpanId，缺失则回落 MDC。</li>
 *     <li>消费时按 metadata 还原 traceId 到 MDC / InvocationContext；spanId 在消费端重新生成，
 *     发布端 spanId 作为新 parentSpanId，构成调用链。</li>
 *     <li>外部系统消息（{@link MqMetadata#isSynthetic()}）没有上游 traceId，
 *     消费端会就地生成新 traceId / spanId，记录在 metadata 中并在 MDC 内可见。</li>
 * </ul>
 *
 * @author pcy
 */
@Slf4j
public final class MqTraceSupport {

    private static final String INVOCATION_CONTEXT_ACCESSOR = "com.cy.core.context.InvocationContextAccessor";
    private static final String INVOCATION_CONTEXT = "com.cy.core.context.InvocationContext";
    private static final String INVOCATION_SCOPE = "com.cy.core.context.InvocationScope";
    private static final String MDC_CLASS = "org.slf4j.MDC";

    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_SPAN_ID = "spanId";
    private static final String MDC_PARENT_SPAN_ID = "parentSpanId";

    /**
     * Span id 的 hex 长度（{@link UUID} 去掉连字符后取前若干位）。
     */
    private static final int SPAN_ID_HEX_LENGTH = 16;

    /**
     * 通过 InvocationContext 写入新上下文时使用的来源标识；
     * 表示 trace 上下文由 MQ 框架在消费端合成。
     */
    private static final String INVOCATION_CONTEXT_SYSTEM_SOURCE = "system";

    private MqTraceSupport() {
    }

    /**
     * 把当前线程的 trace 上下文捕获到 metadata 中。
     * <p>
     * 优先 InvocationContext，回落 MDC；都没有时保留 metadata 现有值（可能为 {@code null}）。
     * </p>
     *
     * @param metadata 待填充的 metadata，必填
     */
    public static void captureInto(MqMetadata metadata) {
        if (metadata == null) {
            return;
        }
        // 1. 先尝试 InvocationContext。
        if (captureFromInvocationContext(metadata)) {
            return;
        }
        // 2. 回落到 MDC。
        captureFromMdc(metadata);
    }

    /**
     * 据 metadata 在当前线程上建立 trace 作用域。
     * <p>
     * 在 try-with-resources 中使用，作用域结束时自动还原原始上下文。
     * 无论 metadata 有无 traceId，本方法都会尝试为消费链路建立可见的 trace 状态。
     * 对于外部消息（{@link MqMetadata#isSynthetic()}），会就地生成新的 traceId 写回 metadata。
     * </p>
     *
     * @param metadata 消息元数据，必填
     * @return 作用域，关闭时还原原始上下文
     */
    public static TraceScope establish(MqMetadata metadata) {
        if (metadata == null) {
            return TraceScope.NOOP;
        }

        // 先保存原上下文。
        Snapshot snapshot = Snapshot.capture();

        // 计算消费端 trace 字段。
        String traceId = metadata.getTraceId();
        String parentSpanId = metadata.getSpanId(); // 发布端 spanId 即消费端 parentSpanId
        if (traceId == null || traceId.isBlank()) {
            // 外部消息或 traceId 缺失：就地生成，并写回 metadata 让业务方可读。
            traceId = newTraceId();
            metadata.setTraceId(traceId);
            parentSpanId = null;
        }
        if (parentSpanId != null && parentSpanId.isBlank()) {
            parentSpanId = null;
        }
        String newSpanId = newSpanId();

        // 设置到 MDC。
        setMdc(MDC_TRACE_ID, traceId);
        setMdc(MDC_SPAN_ID, newSpanId);
        setMdc(MDC_PARENT_SPAN_ID, parentSpanId);

        // 设置到 InvocationContext（如果可用）。
        AutoCloseable invocationScope = openInvocationContextScope(traceId, newSpanId, parentSpanId);

        return new TraceScope(snapshot, invocationScope);
    }

    /**
     * 生成 32 位 hex trace id。
     *
     * @return trace id
     */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 生成 16 位 hex span id。
     *
     * @return span id
     */
    public static String newSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, SPAN_ID_HEX_LENGTH);
    }

    // ============== InvocationContext ==============

    private static boolean captureFromInvocationContext(MqMetadata metadata) {
        try {
            Class<?> accessorClass = Class.forName(INVOCATION_CONTEXT_ACCESSOR);
            Object current = accessorClass.getMethod("current").invoke(null);
            if (current == null) {
                return false;
            }
            Class<?> contextClass = current.getClass();
            String traceId = (String) contextClass.getMethod("getTraceId").invoke(current);
            String spanId = (String) contextClass.getMethod("getSpanId").invoke(current);
            String parentSpanId = (String) contextClass.getMethod("getParentSpanId").invoke(current);
            metadata.setTraceId(traceId);
            metadata.setSpanId(spanId);
            metadata.setParentSpanId(parentSpanId);
            return traceId != null;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (Exception e) {
            log.debug("从 InvocationContext 捕获追踪上下文失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 在 InvocationContext 中安装新的 trace 上下文，返回作用域用于还原。
     *
     * @param traceId      traceId
     * @param spanId       新 spanId
     * @param parentSpanId 父 spanId
     * @return InvocationScope，可能为 {@code null}
     */
    private static AutoCloseable openInvocationContextScope(String traceId, String spanId, String parentSpanId) {
        try {
            Class<?> accessorClass = Class.forName(INVOCATION_CONTEXT_ACCESSOR);
            Class<?> contextClass = Class.forName(INVOCATION_CONTEXT);
            Class<?> scopeClass = Class.forName(INVOCATION_SCOPE);
            Object current = accessorClass.getMethod("current").invoke(null);
            Object newContext = current != null && contextClass.isInstance(current)
                    ? rebuildInvocationContext(current, traceId, spanId, parentSpanId)
                    : createSystemInvocationContext(contextClass, traceId, spanId, parentSpanId);
            Object scope = scopeClass.getMethod("open", contextClass).invoke(null, newContext);
            return scope instanceof AutoCloseable closeable ? closeable : null;
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (Exception e) {
            log.debug("建立 InvocationContext 追踪作用域失败: {}", e.getMessage());
            return null;
        }
    }

    private static Object rebuildInvocationContext(Object current, String traceId, String spanId, String parentSpanId)
            throws Exception {
        try {
            Object builder = current.getClass().getMethod("toBuilder").invoke(current);
            invokeBuilderMethod(builder, "traceId", traceId);
            invokeBuilderMethod(builder, "spanId", spanId);
            invokeBuilderMethod(builder, "parentSpanId", parentSpanId);
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (NoSuchMethodException e) {
            Class<?> contextClass = Class.forName(INVOCATION_CONTEXT);
            return createSystemInvocationContext(contextClass, traceId, spanId, parentSpanId);
        }
    }

    private static Object createSystemInvocationContext(Class<?> contextClass, String traceId, String spanId, String parentSpanId)
            throws Exception {
        try {
            return contextClass.getMethod("system", String.class, String.class, String.class, String.class, String.class)
                    .invoke(null, traceId, spanId, parentSpanId, INVOCATION_CONTEXT_SYSTEM_SOURCE, null);
        } catch (NoSuchMethodException e) {
            return contextClass.getMethod("system", String.class, String.class, String.class, String.class)
                    .invoke(null, traceId, spanId, INVOCATION_CONTEXT_SYSTEM_SOURCE, null);
        }
    }

    private static void invokeBuilderMethod(Object builder, String methodName, String value) throws Exception {
        Method method = builder.getClass().getMethod(methodName, String.class);
        method.invoke(builder, value);
    }

    // ============== MDC ==============

    private static void captureFromMdc(MqMetadata metadata) {
        try {
            Class<?> mdc = Class.forName(MDC_CLASS);
            Method get = mdc.getMethod("get", String.class);
            metadata.setTraceId((String) get.invoke(null, MDC_TRACE_ID));
            metadata.setSpanId((String) get.invoke(null, MDC_SPAN_ID));
            metadata.setParentSpanId((String) get.invoke(null, MDC_PARENT_SPAN_ID));
        } catch (Exception e) {
            log.debug("从 MDC 捕获追踪上下文失败: {}", e.getMessage());
        }
    }

    private static void setMdc(String key, String value) {
        try {
            Class<?> mdc = Class.forName(MDC_CLASS);
            if (value == null) {
                mdc.getMethod("remove", String.class).invoke(null, key);
            } else {
                mdc.getMethod("put", String.class, String.class).invoke(null, key, value);
            }
        } catch (Exception ignored) {
            // MDC 不可用：跳过。
        }
    }

    private static String getMdc(String key) {
        try {
            Class<?> mdc = Class.forName(MDC_CLASS);
            return (String) mdc.getMethod("get", String.class).invoke(null, key);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void restoreMdc(String key, String original) {
        try {
            Class<?> mdc = Class.forName(MDC_CLASS);
            if (original == null) {
                mdc.getMethod("remove", String.class).invoke(null, key);
            } else {
                mdc.getMethod("put", String.class, String.class).invoke(null, key, original);
            }
        } catch (Exception ignored) {
            // MDC 不可用：跳过。
        }
    }

    // ============== TraceScope ==============

    /**
     * trace 作用域。
     * <p>在 try-with-resources 中使用，关闭时自动还原原 trace 上下文。</p>
     */
    public static final class TraceScope implements AutoCloseable {

        /**
         * 空作用域，用于 metadata 为 {@code null} 等场景。
         */
        public static final TraceScope NOOP = new TraceScope(null, null);

        private final Snapshot snapshot;
        private final AutoCloseable invocationScope;
        private boolean closed;

        private TraceScope(Snapshot snapshot, AutoCloseable invocationScope) {
            this.snapshot = snapshot;
            this.invocationScope = invocationScope;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (snapshot != null) {
                snapshot.restore();
            }
            if (invocationScope != null) {
                try {
                    invocationScope.close();
                } catch (Exception e) {
                    log.debug("关闭 InvocationContext 追踪作用域失败: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * trace MDC 快照。
     */
    private record Snapshot(String traceId, String spanId, String parentSpanId) {

        static Snapshot capture() {
            return new Snapshot(getMdc(MDC_TRACE_ID), getMdc(MDC_SPAN_ID), getMdc(MDC_PARENT_SPAN_ID));
        }

        void restore() {
            restoreMdc(MDC_TRACE_ID, traceId);
            restoreMdc(MDC_SPAN_ID, spanId);
            restoreMdc(MDC_PARENT_SPAN_ID, parentSpanId);
        }
    }
}
