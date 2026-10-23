// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;


import com.cy.core.context.InvocationContext;
import com.cy.core.context.InvocationContextAccessor;
import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 只读调用并行预执行器（从 {@link LoopToolRoundEngine} 抽出的执行时机优化职责）。
 * <p>
 * 本轮判定为只读且数量 ≥2 的调用提交执行器并发执行，结果按下标收集
 * （主循环保序挂载）。未启用并行（executor 为 null）或只读调用不足 2 个时
 * 返回空 Map（全串行，零开销）。
 * </p>
 * <p>
 * 语义约束：只读调用按 {@code ToolInvoker#isReadOnly} 契约可在审批屏障前执行
 * （无副作用、幂等、重放安全）；并行阶段只「执行」，消息挂载 / 审批检查 /
 * 动作收集全部回到引擎线程保序进行。
 * </p>
 *
 * @author pcy
 * @since 2026/09/02
 */
final class ReadOnlyPreExecutor {

    private final ToolInvoker toolInvoker;
    private final ToolRoundCodec codec;
    private final ExecutorService readOnlyExecutor;

    ReadOnlyPreExecutor(ToolInvoker toolInvoker, ToolRoundCodec codec,
                        ExecutorService readOnlyExecutor) {
        this.toolInvoker = toolInvoker;
        this.codec = codec;
        this.readOnlyExecutor = readOnlyExecutor;
    }

    /**
     * 只读并行预执行结果载体（结果 / 致命异常 / 已解析参数）。
     *
     * @param result 执行结果（致命失败时为 null）
     * @param fatal  致命异常（无致命失败为 null）
     * @param args   已解析参数（参数解析失败为 null）
     */
    record PreExecuted(ToolCallResult result, FatalToolCallException fatal,
                       Map<String, Object> args) {
    }

    /**
     * 并行预执行本轮的只读调用。
     *
     * @param ctx   运行上下文（取消检查）
     * @param calls 本轮 LLM 请求的 tool_calls
     * @return 下标 → 预执行结果（未启用 / 不满足条件为空 Map）
     */
    Map<Integer, PreExecuted> preExecute(AgentRunContext ctx, List<LoopToolCall> calls) {
        if (readOnlyExecutor == null || ctx.isCancelled()) {
            // 已取消的 run 不再发起并行只读调用（浪费外部调用配额）
            return Map.of();
        }
        List<Integer> readOnlyIndexes = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            if (toolInvoker.isReadOnly(calls.get(i).name())) {
                readOnlyIndexes.add(i);
            }
        }
        if (readOnlyIndexes.size() < 2) {
            return Map.of();
        }
        Map<Integer, PreExecuted> results = new ConcurrentHashMap<>();
        List<CompletableFuture<?>> futures = new ArrayList<>();
        // 并行只读调用跑在执行器线程上，ThreadLocal 调用上下文不自动随行：
        // 在引擎线程捕获，随任务重建（与 ToolTimeoutExecutor 同一约定）。
        InvocationContext invocation = InvocationContextAccessor.current();
        for (int i : readOnlyIndexes) {
            LoopToolCall call = calls.get(i);
            futures.add(CompletableFuture.runAsync(InvocationContextAccessor.wrap(invocation,
                    () -> { results.put(i, preExecuteOne(ctx, call)); }), readOnlyExecutor));
        }
        joinFutures(futures);
        return results;
    }

    /**
     * 等待全部并行预执行完成（非 Fatal 未捕获异常剥壳上抛，与串行路径行为一致）。
     *
     * @param futures 并行任务
     */
    private void joinFutures(List<CompletableFuture<?>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    /**
     * 并行预执行单个只读调用：解析参数并执行，结果 / 致命异常封装为
     * {@link PreExecuted}（不产生消息挂载与事件，全部回引擎线程保序处理）。
     *
     * @param ctx  运行上下文（透传给工具）
     * @param call 只读工具调用
     * @return 预执行结果（args=null 表示参数解析失败）
     */
    private PreExecuted preExecuteOne(AgentRunContext ctx, LoopToolCall call) {
        Map<String, Object> args = codec.parseArgs(call.arguments());
        if (args == null) {
            return new PreExecuted(null, null, null);
        }
        try {
            return new PreExecuted(toolInvoker.invoke(call.id(), call.name(), args, ctx), null, args);
        } catch (FatalToolCallException fte) {
            return new PreExecuted(null, fte, args);
        }
    }
}
