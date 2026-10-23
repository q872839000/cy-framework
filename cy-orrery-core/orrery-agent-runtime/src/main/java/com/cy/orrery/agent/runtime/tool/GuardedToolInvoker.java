// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.tool;


import com.cy.orrery.agent.runtime.constant.RuntimeErrorCode;
import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.event.Events;
import com.cy.orrery.agent.runtime.loop.ToolCallResult;
import com.cy.orrery.agent.runtime.loop.ToolInvoker;
import com.cy.orrery.agent.runtime.loop.ToolRoundHooks;
import java.util.Map;

/**
 * 带护栏的工具执行器：宿主 ToolInvoker 的统一包装（装饰器）。
 * <p>
 * 执行管线：指纹熔断检查（命中入口拒绝）→ TOOL_STARTED 事件 → 超时执行 →
 * 失败升级记账（达阈值注入换路指引 + milestone）→ 指纹熔断记账 →
 * TOOL_FINISHED 事件（含 durationMs / success / errorCode）。
 * </p>
 * <p>
 * 事件契约：本类是工具级事件（TOOL_STARTED / TOOL_FINISHED）的唯一发射者；
 * 被包装的 ToolInvoker 及宿主适配器不得再发（见 ToolInvoker 契约）。
 * 主路径与审批重放路径共享同一条管线（引擎两路径都经 ToolInvoker 边界调用）。
 * </p>
 * <p>
 * {@link #close} 时级联关闭内部超时执行器线程池。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public final class GuardedToolInvoker implements ToolInvoker, AutoCloseable {

    private final ToolInvoker delegate;
    private final ToolHarnessConfig config;
    private final ToolRoundHooks hooks;
    private final ToolTimeoutExecutor timeoutExecutor;
    private final ArgFingerprintFuse argFuse;
    private final FailureStreakEscalator streakEscalator;

    /**
     * 包装宿主工具执行器。
     *
     * @param delegate 被包装的工具执行 SPI（宿主领域工具适配）
     * @param config   harness 配置
     * @param hooks    领域钩子（升级文案供给；可为 {@link ToolRoundHooks#NO_OP}）
     * @return 带护栏的工具执行器
     */
    public static GuardedToolInvoker wrap(ToolInvoker delegate, ToolHarnessConfig config,
                                          ToolRoundHooks hooks) {
        return new GuardedToolInvoker(delegate, config, hooks);
    }

    private GuardedToolInvoker(ToolInvoker delegate, ToolHarnessConfig config,
                               ToolRoundHooks hooks) {
        this.delegate = delegate;
        this.config = config;
        this.hooks = hooks == null ? ToolRoundHooks.NO_OP : hooks;
        this.timeoutExecutor = new ToolTimeoutExecutor(config.timeoutMillis());
        this.argFuse = new ArgFingerprintFuse(config.argFuseThreshold());
        this.streakEscalator = new FailureStreakEscalator(config.failureStreakThreshold());
    }

    /**
     * 执行带护栏的工具调用管线。
     *
     * @param callId    调用 ID
     * @param toolName  工具名
     * @param arguments 已解析参数
     * @param ctx       运行上下文
     * @return 执行结果
     */
    @Override
    public ToolCallResult invoke(String callId, String toolName, Map<String, Object> arguments,
                                 AgentRunContext ctx) {
        if (argFuse.shouldReject(ctx, toolName, arguments)) {
            RuntimeErrorCode code = RuntimeErrorCode.TOOL_ARG_FUSE;
            return ToolCallResult.fail(callId, toolName, code.code(), code.format(config.argFuseThreshold()), null);
        }
        Events.toolStarted(ctx.getBus(), callId, toolName);
        long start = System.currentTimeMillis();
        ToolCallResult result = null;
        try {
            result = timeoutExecutor.execute(delegate, callId, toolName, arguments, ctx);
        } finally {
            Events.toolFinished(ctx.getBus(), callId, toolName,
                    System.currentTimeMillis() - start,
                    result != null && result.success(),
                    result == null ? null : result.errorCode());
        }
        return settle(ctx, toolName, arguments, result);
    }

    /**
     * 成功 / 失败记账与升级注入。
     *
     * @param ctx       运行上下文
     * @param toolName  工具名
     * @param arguments 已解析参数
     * @param result    工具执行结果
     * @return 处理后的结果（失败升级时消息被增强）
     */
    private ToolCallResult settle(AgentRunContext ctx, String toolName,
                                  Map<String, Object> arguments, ToolCallResult result) {
        if (result.success()) {
            argFuse.recordSuccess(ctx, toolName, arguments);
            streakEscalator.recordSuccess(ctx, toolName);
            return result;
        }
        argFuse.recordFailure(ctx, toolName, arguments);
        String escalation = streakEscalator.recordFailure(ctx, hooks, toolName);
        if (escalation != null) {
            String enhanced = result.errorMessage() == null
                    ? escalation
                    : result.errorMessage() + '\n' + escalation;
            return new ToolCallResult(result.callId(), result.toolName(), false,
                    result.resultJson(), result.userMessage(), result.errorCode(), enhanced,
                    result.partialFailure());
        }
        return result;
    }

    /**
     * 是否只读工具（透传被包装者判定）。
     *
     * @param toolName 工具名
     * @return true 表示只读
     */
    @Override
    public boolean isReadOnly(String toolName) {
        return delegate.isReadOnly(toolName);
    }

    /**
     * 关闭内部超时执行器线程池。
     */
    @Override
    public void close() {
        timeoutExecutor.close();
    }
}
