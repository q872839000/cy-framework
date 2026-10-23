package com.cy.log.reactive;

import com.cy.core.context.InvocationContext;
import com.cy.log.access.AccessTraceState;
import com.cy.log.config.LogProperties;
import com.cy.log.core.LogPublisher;
import com.cy.log.core.LogRecord;
import com.cy.log.payload.LogPayloadProcessor;
import com.cy.log.util.LogUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.context.Context;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 响应式日志支持工具
 * <p>
 * 当方法返回 {@link Mono} 或 {@link Flux} 时，将日志采集延迟到响应式流真正完成时执行，
 * 而非在 publisher 对象创建时执行。
 * <p>
 * <b>所有日志采集和发布逻辑都在 {@code doFinally} 中统一处理</b>，确保 success / error / cancel
 * 三种信号都能正确完成日志闭环。
 * <p>
 * <b>注意</b>：此类直接引用 {@code reactor-core}，只有在 {@code reactor.core.publisher.Mono}
 * 存在于类路径时才会被加载，非响应式环境下不会触发 ClassNotFoundException。
 *
 * @author pcy
 * @date 2026/04/14
 */
public final class ReactiveLogSupport {

    private static final String CANCELLED_MESSAGE = "Reactive stream cancelled";

    private ReactiveLogSupport() {
    }

    /**
     * 判断对象是否为 Reactor 响应式类型
     */
    public static boolean isReactive(Object result) {
        return result instanceof Mono<?> || result instanceof Flux<?>;
    }

    /**
     * 响应式流被取消时使用的统一错误消息。
     */
    public static String cancelledMessage() {
        return CANCELLED_MESSAGE;
    }

    // ==================== @SysLog ====================

    /**
     * 为 @SysLog 场景包装 Mono/Flux。
     * <p>
     * <b>Mono</b>：在流完成时采集 afterData、changeSummary 并发布日志。<br>
     * <b>Flux</b>：多值流无法以单个值记录 afterData/changeSummary，仅采集成功/失败/耗时。<br>
     * <b>Cancel</b>：标记为失败并附带统一取消消息。
     *
     * @param result        方法返回的 Mono 或 Flux
     * @param logRecord     已部分填充的日志记录
     * @param recordAfter   是否记录 afterData（仅 Mono 有效）
     * @param rawBeforeData 修改前数据（用于生成 changeSummary，仅 Mono 有效）
     * @param startTime     方法调用开始时间
     * @param properties    日志配置
     * @param publisher     日志发布器
     * @return 包装后的 Mono/Flux（调用方必须原样返回以保持响应式链路）
     */
    public static Object wrapForSysLog(Object result,
                                       LogRecord logRecord,
                                       boolean recordAfter,
                                       Object rawBeforeData,
                                       long startTime,
                                       LogProperties properties,
                                       LogPublisher publisher,
                                       LogPayloadProcessor payloadProcessor) {
        LogPayloadProcessor processor = payloadProcessor != null
                ? payloadProcessor
                : new LogPayloadProcessor(properties);
        if (result instanceof Mono<?> mono) {
            AtomicReference<Object> valueRef = new AtomicReference<>();
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            return mono
                    .doOnSuccess(valueRef::set)
                    .doOnError(errorRef::set)
                    .doFinally(signal -> {
                        Throwable error = errorRef.get();
                        if (error != null) {
                            onSysLogError(error, logRecord);
                        } else if (signal == SignalType.CANCEL) {
                            logRecord.setSuccess(false);
                            logRecord.setErrorMessage(CANCELLED_MESSAGE);
                        } else {
                            onSysLogSuccess(valueRef.get(), logRecord, recordAfter, rawBeforeData, processor);
                        }
                        logRecord.setDuration(System.currentTimeMillis() - startTime);
                        publisher.publish(logRecord);
                    });
        }

        if (result instanceof Flux<?> flux) {
            // Flux 是多值流，无法以单个值记录 afterData/changeSummary
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            return flux
                    .doOnError(errorRef::set)
                    .doFinally(signal -> {
                        Throwable error = errorRef.get();
                        if (error != null) {
                            onSysLogError(error, logRecord);
                        } else if (signal == SignalType.CANCEL) {
                            logRecord.setSuccess(false);
                            logRecord.setErrorMessage(CANCELLED_MESSAGE);
                        } else {
                            logRecord.setSuccess(true);
                        }
                        logRecord.setDuration(System.currentTimeMillis() - startTime);
                        publisher.publish(logRecord);
                    });
        }

        return result;
    }

    // ==================== @AccessLog ====================

    /**
     * AccessLog 响应式完成回调。
     * <p>
     * 此接口不暴露 Reactor 类型，调用方无需依赖 reactor-core。
     */
    @FunctionalInterface
    public interface ReactiveAccessLogCallback {
        /**
         * 流完成时回调。
         *
         * @param value     成功值（Mono 成功时为实际值，Flux/cancel/error 时为 null）
         * @param error     异常（失败时非 null）
         * @param cancelled 是否因取消而结束
         */
        void onComplete(Object value, Throwable error, boolean cancelled);
    }

    /**
     * 为 @AccessLog 场景包装 Mono/Flux。
     * <p>
     * 使用 {@code doFinally} 作为唯一完成路径，确保 success / error / cancel 三种信号
     * 都会触发回调，调用方在回调中完成 span 数据采集和发布。
     * <p>
     * <b>回调可能在任意线程执行</b>，调用方不得在回调中访问 ThreadLocal。
     *
     * @param result   方法返回的 Mono 或 Flux
     * @param state    当前 ACCESS trace 状态，用于在 Reactor 链路中传播上下文
     * @param invocationContext 当前活动 span 对应的调用上下文快照，用于恢复 spanId / parentSpanId
     * @param callback 完成回调
     * @return 包装后的 Mono/Flux
     */
    public static Object wrapForAccessLog(Object result,
                                          AccessTraceState state,
                                          InvocationContext invocationContext,
                                          ReactiveAccessLogCallback callback) {
        if (result instanceof Mono<?> mono) {
            AtomicReference<Object> valueRef = new AtomicReference<>();
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            return mono
                    .doOnSuccess(valueRef::set)
                    .doOnError(errorRef::set)
                    .doFinally(signal -> callback.onComplete(
                            valueRef.get(), errorRef.get(), signal == SignalType.CANCEL))
                    .contextWrite(context -> putAccessState(context, state, invocationContext));
        }

        if (result instanceof Flux<?> flux) {
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            return flux
                    .doOnError(errorRef::set)
                    .doFinally(signal -> callback.onComplete(
                            null, errorRef.get(), signal == SignalType.CANCEL))
                    .contextWrite(context -> putAccessState(context, state, invocationContext));
        }

        return result;
    }

    /**
     * 为已存在的 Mono/Flux 仅传播 ACCESS trace 状态与调用上下文，不附加完成回调。
     */
    public static Object propagateAccessState(Object result, AccessTraceState state, InvocationContext invocationContext) {
        if (result instanceof Mono<?> mono) {
            return mono.contextWrite(context -> putAccessState(context, state, invocationContext));
        }
        if (result instanceof Flux<?> flux) {
            return flux.contextWrite(context -> putAccessState(context, state, invocationContext));
        }
        return result;
    }

    // ==================== 内部方法 ====================

    private static void onSysLogSuccess(Object value, LogRecord logRecord,
                                        boolean recordAfter, Object rawBeforeData,
                                        LogPayloadProcessor processor) {
        logRecord.setSuccess(true);
        if (recordAfter && value != null) {
            // 走统一载荷处理器，文件 / 流 / 大对象不会再 OOM
            logRecord.setAfterData(processor.process(value));
        }
        if (rawBeforeData != null && value != null && recordAfter) {
            String summary = processor.processChangeSummary(rawBeforeData, value);
            if (summary != null && !summary.isEmpty()) {
                logRecord.setChangeSummary(summary);
            }
        }
    }

    private static void onSysLogError(Throwable error, LogRecord logRecord) {
        logRecord.setSuccess(false);
        logRecord.setErrorMessage(error.getMessage());
        logRecord.setStackTrace(LogUtils.getStackTrace(error));
    }

    /**
     * 将 ACCESS trace 状态与调用上下文写入 Reactor Context。
     */
    private static Context putAccessState(Context context, AccessTraceState state, InvocationContext invocationContext) {
        Context next = state != null ? context.put(AccessTraceState.class, state) : context;
        return invocationContext != null ? next.put(InvocationContext.class, invocationContext) : next;
    }
}
