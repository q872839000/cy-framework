// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.tool;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.AttributeKey;
import com.cy.orrery.agent.runtime.event.Events;
import com.cy.orrery.agent.runtime.loop.ToolRoundHooks;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单工具失败升级器：同一工具连续失败达阈值时升级提示（milestone 事件 + 回执注入换路指引）。
 * <p>
 * 按 (runId, 工具名) 计数连续失败，成功清零；达到阈值后每次失败持续注入升级文案
 * （经 {@link ToolRoundHooks#toolFailureEscalation} 供给），并发射一次 milestone 事件。
 * 计数存于运行上下文属性，随 run 生命周期自动消亡。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class FailureStreakEscalator {

    /** 连续失败计数表键名（检查点序列化器按此键做强类型注册）。 */
    public static final String STREAK_COUNTS_KEY_NAME = "tool.failureStreaks";

    /** 已升级标记表键名。 */
    public static final String ESCALATED_KEY_NAME = "tool.escalated";

    @SuppressWarnings("unchecked")
    private static final AttributeKey<Map<String, Integer>> STREAK_COUNTS =
            (AttributeKey) new AttributeKey(STREAK_COUNTS_KEY_NAME, Map.class);

    @SuppressWarnings("unchecked")
    private static final AttributeKey<Map<String, Boolean>> ESCALATED =
            (AttributeKey) new AttributeKey(ESCALATED_KEY_NAME, Map.class);

    private final int threshold;

    /**
     * 构造升级器。
     *
     * @param threshold 连续失败升级阈值（≤0 关闭升级）
     */
    public FailureStreakEscalator(int threshold) {
        this.threshold = threshold;
    }

    /**
     * 失败记账：计数 +1；达阈值发一次 milestone，返回升级文案（未达阈值返回 null）。
     *
     * @param ctx      运行上下文
     * @param hooks    领域钩子（升级文案供给）
     * @param toolName 工具名
     * @return 升级文案；未达阈值或已升级过（milestone 只发一次，文案持续注入）时语义见返回值
     */
    public String recordFailure(AgentRunContext ctx, ToolRoundHooks hooks, String toolName) {
        if (threshold <= 0) {
            return null;
        }
        String key = ctx.getRunId() + "|" + toolName;
        int streak = counts(ctx).merge(key, 1, Integer::sum);
        if (streak < threshold) {
            return null;
        }
        if (escalated(ctx).putIfAbsent(key, Boolean.TRUE) == null) {
            Events.milestone(ctx.getBus(), "工具 " + toolName + " 已连续失败 " + streak + " 次");
        }
        return hooks.toolFailureEscalation(toolName, streak);
    }

    /**
     * 成功记账：清零连续失败计数与升级标记。
     *
     * @param ctx      运行上下文
     * @param toolName 工具名
     */
    public void recordSuccess(AgentRunContext ctx, String toolName) {
        if (threshold <= 0) {
            return;
        }
        String key = ctx.getRunId() + "|" + toolName;
        counts(ctx).remove(key);
        escalated(ctx).remove(key);
    }

    /**
     * 连续失败计数表（惰性初始化）。
     *
     * @param ctx 运行上下文
     * @return 计数表
     */
    private Map<String, Integer> counts(AgentRunContext ctx) {
        Map<String, Integer> counts = ctx.getAttr(STREAK_COUNTS);
        if (counts == null) {
            counts = new ConcurrentHashMap<>();
            ctx.putAttr(STREAK_COUNTS, counts);
        }
        return counts;
    }

    /**
     * 已升级标记表（milestone 只发一次）。
     *
     * @param ctx 运行上下文
     * @return 标记表
     */
    private Map<String, Boolean> escalated(AgentRunContext ctx) {
        Map<String, Boolean> flags = ctx.getAttr(ESCALATED);
        if (flags == null) {
            flags = new ConcurrentHashMap<>();
            ctx.putAttr(ESCALATED, flags);
        }
        return flags;
    }
}
