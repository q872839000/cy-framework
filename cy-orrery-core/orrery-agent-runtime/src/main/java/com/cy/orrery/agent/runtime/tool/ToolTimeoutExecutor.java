// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.tool;


import com.cy.core.context.InvocationContext;
import com.cy.core.context.InvocationContextAccessor;
import com.cy.orrery.agent.runtime.constant.RuntimeErrorCode;
import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.RunCancellationException;
import com.cy.orrery.agent.runtime.loop.ToolCallResult;
import com.cy.orrery.agent.runtime.loop.ToolInvoker;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具超时执行器：把同步工具调用托管到受控执行器并限时等待。
 * <p>
 * 工具跑在独立虚拟线程（按任务创建，线程名 {@code tool-exec-实例-序号} 便于 dump 定位），
 * 超时 interrupt 强取消（工具内部须响应中断）；超时返回 TOOL_TIMEOUT 失败回执
 * （可重试语义，LLM 可调整参数重试，且计入 GuardedToolInvoker 的失败连击 / 指纹熔断）。
 * </p>
 * <p>
 * 为何虚拟线程：原平台固定 8 线程会被无响应工具占死——超时后 future.cancel(true)
 * 只发中断，忽略中断的顽固工具仍占住线程，并发运行稍多即全员饿死；
 * 虚拟线程按任务创建，占用仅是内存（MB 级栈 → KB 级），顽固工具最多浪费自身
 * 一个载体线程槽位，池不再有"被占满"的排队能力（每个任务即时有线程）。
 * 执行器实例级共享（多 run 共用），{@link #close} 时关闭。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class ToolTimeoutExecutor implements AutoCloseable {

    /** 工具执行线程名前缀（后接实例序号与任务序号，形如 tool-exec-1-0，便于 dump 定位）。 */
    private static final String THREAD_NAME_PREFIX = "tool-exec-";

    /** 执行器实例全局序号（多实例共存时线程名不撞车）。 */
    private static final AtomicInteger INSTANCE_SEQ = new AtomicInteger();

    private final ExecutorService pool;
    private final long timeoutMillis;

    /**
     * 构造超时执行器。
     *
     * @param timeoutMillis 超时毫秒
     */
    public ToolTimeoutExecutor(long timeoutMillis) {
        // name(prefix, start) 两参形态：工厂内任务序号自增拼在前缀后（单参 name 是全线程同名，
        // dump 时无法区分任务）；实例序号编入前缀，保证跨实例唯一
        this.pool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
                .name(THREAD_NAME_PREFIX + INSTANCE_SEQ.incrementAndGet() + "-", 0).factory());
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 限时执行工具调用。
     *
     * @param delegate  被包装的工具执行 SPI
     * @param callId    调用 ID
     * @param toolName  工具名
     * @param arguments 已解析参数
     * @param ctx       运行上下文
     * @return 执行结果（超时返回 TOOL_TIMEOUT 失败回执）
     * @throws RunCancellationException 运行已取消（等待线程被中断时）
     */
    public ToolCallResult execute(ToolInvoker delegate, String callId, String toolName,
                                  Map<String, Object> arguments, AgentRunContext ctx) {
        // 工具跑在独立虚拟线程上，ThreadLocal 调用上下文不会自动随行：
        // 在提交线程捕获，在执行线程重建（含 MDC），保证工具内的
        // 审计字段填充 / 登录用户判定（如记忆归属解析）与请求线程一致。
        InvocationContext invocation = InvocationContextAccessor.current();
        Future<ToolCallResult> future = pool.submit(
                InvocationContextAccessor.wrap(invocation,
                        () -> delegate.invoke(callId, toolName, arguments, ctx)));
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolCallResult.fail(callId, toolName,
                    RuntimeErrorCode.TOOL_TIMEOUT.code(),
                    RuntimeErrorCode.TOOL_TIMEOUT.format(timeoutMillis),
                    null);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("工具执行异常: " + toolName, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            if (ctx.isCancelled()) {
                // 中断源于运行取消：走统一取消路径，宿主按 CANCELLED 终态收敛。
                throw new RunCancellationException(ctx.getRunId(), "tool_wait_" + toolName);
            }
            throw new IllegalStateException("工具执行被中断: " + toolName, e);
        }
    }

    /**
     * 关闭执行器（在途任务发中断）。
     */
    @Override
    public void close() {
        pool.shutdownNow();
    }
}
