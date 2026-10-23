// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.AttributeKey;
import java.util.ArrayList;
import java.util.List;

/**
 * 循环引擎的上下文属性存储约定。
 * <p>
 * 业务动作日志与审批暂缓调用经 {@link AgentRunContext} 属性表携带，
 * 宿主序列化持久化上下文时随属性一并保存，跨挂起-恢复（审批 / 澄清 / 续跑）不丢。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public final class LoopAttributes {

    /** 业务动作日志键名（检查点序列化器按此键做强类型注册）。 */
    public static final String ACTION_LOG_KEY_NAME = "loop.actionLog";

    /** 审批暂缓调用键名。 */
    public static final String DEFERRED_CALLS_KEY_NAME = "loop.deferredCalls";

    /** 待审批请求键名。 */
    public static final String PENDING_APPROVALS_KEY_NAME = "loop.pendingApprovals";

    /** 跨挂起段累计轮次键名。 */
    public static final String TOTAL_ROUNDS_KEY_NAME = "loop.totalRounds";

    /** 跨挂起段累计工具调用次数键名。 */
    public static final String TOTAL_TOOL_CALLS_KEY_NAME = "loop.totalToolCalls";

    /** 跨挂起段累计 prompt token 键名。 */
    public static final String TOTAL_PROMPT_TOKENS_KEY_NAME = "loop.totalPromptTokens";

    /** 跨挂起段累计 completion token 键名。 */
    public static final String TOTAL_COMPLETION_TOKENS_KEY_NAME = "loop.totalCompletionTokens";

    /** 业务动作日志键（成功执行的业务动作叙述，跨轮累积）。 */
    @SuppressWarnings("unchecked")
    static final AttributeKey<List<String>> ACTION_LOG =
            new AttributeKey(ACTION_LOG_KEY_NAME, List.class);

    /** 审批暂缓调用键（屏障命中后待重放的调用）。 */
    @SuppressWarnings("unchecked")
    static final AttributeKey<List<DeferredToolCall>> DEFERRED_CALLS =
            new AttributeKey(DEFERRED_CALLS_KEY_NAME, List.class);

    /** 待审批请求键（屏障命中后待决定的审批；运行门面挂起持久化的输入）。 */
    @SuppressWarnings("unchecked")
    static final AttributeKey<List<ApprovalRequest>> PENDING_APPROVALS =
            new AttributeKey(PENDING_APPROVALS_KEY_NAME, List.class);

    /** 跨挂起段累计轮次键（续跑遥测：RUN_COMPLETED 事件用两段之和）。 */
    private static final AttributeKey<Integer> TOTAL_ROUNDS =
            new AttributeKey<>(TOTAL_ROUNDS_KEY_NAME, Integer.class);

    /** 跨挂起段累计工具调用次数键。 */
    private static final AttributeKey<Integer> TOTAL_TOOL_CALLS =
            new AttributeKey<>(TOTAL_TOOL_CALLS_KEY_NAME, Integer.class);

    /** 跨挂起段累计 prompt token 键。 */
    private static final AttributeKey<Integer> TOTAL_PROMPT_TOKENS =
            new AttributeKey<>(TOTAL_PROMPT_TOKENS_KEY_NAME, Integer.class);

    /** 跨挂起段累计 completion token 键。 */
    private static final AttributeKey<Integer> TOTAL_COMPLETION_TOKENS =
            new AttributeKey<>(TOTAL_COMPLETION_TOKENS_KEY_NAME, Integer.class);

    private LoopAttributes() {
    }

    /**
     * 读取业务动作日志（不存在时创建空列表并存回）。
     *
     * @param ctx 运行上下文
     * @return 业务动作日志（可变列表）
     */
    public static List<String> actionLog(AgentRunContext ctx) {
        List<String> log = ctx.getAttr(ACTION_LOG);
        if (log == null) {
            log = new ArrayList<>();
            ctx.putAttr(ACTION_LOG, log);
        }
        return log;
    }

    /**
     * 读取审批暂缓调用（不存在时返回空列表，不写回）。
     *
     * @param ctx 运行上下文
     * @return 暂缓调用列表
     */
    public static List<DeferredToolCall> deferredCalls(AgentRunContext ctx) {
        List<DeferredToolCall> calls = ctx.getAttr(DEFERRED_CALLS);
        return calls == null ? List.of() : calls;
    }

    /**
     * 暂存审批暂缓调用。
     *
     * @param ctx   运行上下文
     * @param calls 暂缓调用列表
     */
    public static void stashDeferredCalls(AgentRunContext ctx, List<DeferredToolCall> calls) {
        ctx.putAttr(DEFERRED_CALLS, new ArrayList<>(calls));
    }

    /**
     * 清空审批暂缓调用（重放完成后调用）。
     *
     * @param ctx 运行上下文
     */
    public static void clearDeferredCalls(AgentRunContext ctx) {
        ctx.putAttr(DEFERRED_CALLS, null);
    }

    /**
     * 读取待审批请求（不存在时返回空列表，不写回）。
     *
     * @param ctx 运行上下文
     * @return 待审批请求列表
     */
    public static List<ApprovalRequest> pendingApprovals(AgentRunContext ctx) {
        List<ApprovalRequest> approvals = ctx.getAttr(PENDING_APPROVALS);
        return approvals == null ? List.of() : approvals;
    }

    /**
     * 暂存待审批请求。
     *
     * @param ctx       运行上下文
     * @param approvals 待审批请求列表
     */
    public static void stashPendingApprovals(AgentRunContext ctx, List<ApprovalRequest> approvals) {
        ctx.putAttr(PENDING_APPROVALS, new ArrayList<>(approvals));
    }

    /**
     * 清空待审批请求（审批决定注入完成 / 重放结束后调用）。
     *
     * @param ctx 运行上下文
     */
    public static void clearPendingApprovals(AgentRunContext ctx) {
        ctx.putAttr(PENDING_APPROVALS, null);
    }

    // ========================= 跨挂起段运行遥测累计 =========================

    /**
     * 累计一段运行（一次 engine.run 调用）的遥测进属性表。
     * <p>
     * 挂起（审批 / 澄清）不是运行终点：引擎每段 run 结束时把本段累加器累计进来，
     * 终态事件的遥测取累计值（多段之和），不为挂起清零。
     * </p>
     *
     * @param ctx 运行上下文
     * @param acc 本段循环累加器
     */
    public static void accumulateRunTotals(AgentRunContext ctx, LoopAccumulator acc) {
        merge(ctx, TOTAL_ROUNDS, acc.rounds);
        merge(ctx, TOTAL_TOOL_CALLS, acc.toolCallCount);
        merge(ctx, TOTAL_PROMPT_TOKENS, acc.promptTokens);
        merge(ctx, TOTAL_COMPLETION_TOKENS, acc.completionTokens);
    }

    /**
     * 读跨挂起段累计轮次。
     *
     * @param ctx 运行上下文
     * @return 累计轮次（无累计为 0）
     */
    public static int totalRounds(AgentRunContext ctx) {
        return ctx.getAttr(TOTAL_ROUNDS, 0);
    }

    /**
     * 读跨挂起段累计工具调用次数。
     *
     * @param ctx 运行上下文
     * @return 累计调用次数
     */
    public static int totalToolCalls(AgentRunContext ctx) {
        return ctx.getAttr(TOTAL_TOOL_CALLS, 0);
    }

    /**
     * 读跨挂起段累计 prompt token。
     *
     * @param ctx 运行上下文
     * @return 累计 prompt token
     */
    public static int totalPromptTokens(AgentRunContext ctx) {
        return ctx.getAttr(TOTAL_PROMPT_TOKENS, 0);
    }

    /**
     * 读跨挂起段累计 completion token。
     *
     * @param ctx 运行上下文
     * @return 累计 completion token
     */
    public static int totalCompletionTokens(AgentRunContext ctx) {
        return ctx.getAttr(TOTAL_COMPLETION_TOKENS, 0);
    }

    /**
     * 清零跨挂起段遥测累计（真终态后调用，防同上下文复用残留）。
     *
     * @param ctx 运行上下文
     */
    public static void clearRunTotals(AgentRunContext ctx) {
        ctx.putAttr(TOTAL_ROUNDS, null);
        ctx.putAttr(TOTAL_TOOL_CALLS, null);
        ctx.putAttr(TOTAL_PROMPT_TOKENS, null);
        ctx.putAttr(TOTAL_COMPLETION_TOKENS, null);
    }

    /**
     * 整数属性累加。
     *
     * @param ctx   运行上下文
     * @param key   属性键
     * @param delta 增量
     */
    private static void merge(AgentRunContext ctx, AttributeKey<Integer> key, int delta) {
        if (delta == 0) {
            return;
        }
        ctx.putAttr(key, ctx.getAttr(key, 0) + delta);
    }
}
