// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.intent;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 意图路由器：意图键 → 执行策略的分发点（策略注册表 + 兜底）。
 * <p>
 * 意图识别本身（taxonomy、分类调用、判定 prompt）是领域的事；本类只承接
 * 「拿到意图键之后怎么分发」——替代宿主运行器里的 if-else 链。未注册的
 * 意图键落到兜底策略（如闲聊或澄清策略）；无兜底时快速失败（装配错误）。
 * </p>
 *
 * @param <R> 宿主定义的策略结果类型
 * @author pcy
 * @since 2026/08/19
 */
public final class IntentRouter<R> {

    private final Map<String, IntentStrategy<R>> strategies = new ConcurrentHashMap<>();
    private volatile IntentStrategy<R> fallback;

    /**
     * 构造路由器。
     *
     * @param fallback 兜底策略（未注册意图键时执行；可为 null 表示不兜底）
     */
    public IntentRouter(IntentStrategy<R> fallback) {
        this.fallback = fallback;
    }

    /**
     * 注册策略（策略名重复时快速失败）。
     *
     * @param strategy 策略
     * @throws IllegalArgumentException 策略名已注册
     */
    public void register(IntentStrategy<R> strategy) {
        Objects.requireNonNull(strategy, "strategy");
        IntentStrategy<R> previous = strategies.putIfAbsent(strategy.name(), strategy);
        if (previous != null) {
            throw new IllegalArgumentException("意图策略已注册: " + strategy.name());
        }
    }

    /**
     * 按意图键分发执行。
     *
     * @param ctx       运行上下文
     * @param intentKey 意图键（宿主意图识别产物；可为 null，落兜底）
     * @return 策略执行结果
     * @throws IllegalStateException 意图键未注册且无兜底策略
     */
    public R dispatch(AgentRunContext ctx, String intentKey) {
        IntentStrategy<R> strategy = intentKey == null ? null : strategies.get(intentKey);
        if (strategy == null) {
            strategy = fallback;
        }
        if (strategy == null) {
            throw new IllegalStateException("未注册意图策略且无兜底: " + intentKey);
        }
        return strategy.execute(ctx);
    }

    /**
     * 意图键是否已注册（含兜底语义判定）。
     *
     * @param intentKey 意图键
     * @return true 表示可分发（已注册或有兜底）
     */
    public boolean routable(String intentKey) {
        return (intentKey != null && strategies.containsKey(intentKey)) || fallback != null;
    }
}
