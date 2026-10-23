package com.cy.log.access;

import lombok.Data;


/**
 * 调用链路 Span
 * <p>
 * 代表一次方法调用的完整信息，包括类名、方法名、入参、出参、耗时等。
 * 同一请求中多个嵌套调用通过 {@link #depth} 标识层级关系。
 *
 * @author pcy
 * @date 2026/04/09
 */
@Data
public class AccessSpan {

    /** 成功结果码。 */
    public static final String RESULT_SUCCESS = "0";

    /** 异常结果码。 */
    public static final String RESULT_ERROR = "-1";

    /** 响应式取消结果码。 */
    public static final String RESULT_CANCELLED = "-2";

    /**
     * 调用类名（简单名，如 RoleServiceImpl）
     */
    private String className;

    /**
     * 调用方法名
     */
    private String methodName;

    /**
     * 当前方法级 span 的唯一 ID。
     * <p>
     * 持久化到 ACCESS 日志表时映射为 {@code spanId}，
     * 用于与其他 span 的 {@link #parentSpanId} 建立父子关系。
     * </p>
     */
    private String spanId;

    /**
     * 直接父级 span 的 ID。
     * <p>
     * 根 span 使用入口 {@link com.cy.core.context.InvocationContext#getParentSpanId()}，
     * 子 span 则指向父 {@link #spanId}，从而统一覆盖跨服务与同服务调用链关系。
     * </p>
     */
    private String parentSpanId;

    /**
     * 调用栈深度（1 = 入口）
     */
    private int depth;

    /**
     * 调用开始时间（毫秒时间戳）
     */
    private long startTime;

    /**
     * 总耗时（毫秒）
     */
    private long duration;

    /**
     * 子方法累计耗时（毫秒），用于计算「实际执行耗时」
     * <p>
     * 响应式完成信号可能跨线程到达，因此通过同步方法维护该字段，
     * 避免父子 span 在回填 childDuration 时产生并发写入问题。
     */
    private long childDuration;

    /**
     * 入参 JSON 字符串
     */
    private String inputArgs;

    /**
     * 出参 JSON 字符串
     */
    private String returnValue;

    /**
     * 直接父级 span，用于在完成当前 span 时回填父级 childDuration。
     */
    private AccessSpan parent;

    /**
     * 是否成功完成。
     * <p>
     * 该字段作为最终落库成功态的唯一判断依据，避免外部再通过 resultCode 魔法值推断。
     */
    private boolean success = true;

    /**
     * 结果码（见 {@link #RESULT_SUCCESS} / {@link #RESULT_ERROR} / {@link #RESULT_CANCELLED}）。
     */
    private String resultCode = RESULT_SUCCESS;

    /**
     * 错误消息
     */
    private String errorMessage;

    /**
     * 模块名称（来自 @AccessIntercept.module）
     */
    private String module;

    /**
     * 操作描述（来自 @AccessIntercept.operationDescribe）
     */
    private String operationDescribe;

    /**
     * 是否已完成
     */
    private boolean completed;

    /**
     * 挂起中的异步续体数量。
     * <p>
     * 当一个 span 的实际完成时机被转交给异步任务或响应式回调时，
     * 通过该计数防止主线程过早将其从未完成栈中移除。
     * </p>
     */
    private int asyncContinuationCount;

    /**
     * 获取实际执行耗时（总耗时 - 子方法耗时）
     */
    public synchronized long getActualDuration() {
        return Math.max(0, duration - childDuration);
    }

    /**
     * 累加子方法耗时
     */
    public synchronized void addChildDuration(long millis) {
        this.childDuration += millis;
    }

    /**
     * 预留一个异步续体占位。
     */
    public synchronized void reserveAsyncContinuation() {
        this.asyncContinuationCount++;
    }

    /**
     * 释放一个异步续体占位。
     *
     * @return 释放后是否已无挂起续体
     */
    public synchronized boolean releaseAsyncContinuation() {
        if (asyncContinuationCount > 0) {
            asyncContinuationCount--;
        }
        return asyncContinuationCount == 0;
    }

    /**
     * 判断当前 span 是否仍有未结束的异步续体。
     */
    public synchronized boolean hasPendingAsyncContinuation() {
        return asyncContinuationCount > 0;
    }
}
