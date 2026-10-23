// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.bridge.cyai;

import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.AttributeKey;

/**
 * 运行级 LLM 模型目标属性（通用载体）。
 * <p>
 * 宿主在运行上下文创建时把模型目标写入属性表（检查点落盘 / 续跑 / 重放自动沿用），
 * 再以其 {@link #resolve(AgentRunContext)} 作为 {@link AiFacadeLlmInvoker} 的
 * 模型目标解析器，即完成"运行级模型覆盖"接线。
 * </p>
 * <p>
 * 跨服务场景（如 Butler 路由下游 Agent）：由调用方协议字段携带 provider / model，
 * 下游宿主在起运行上下文时 {@link #applyTo(AgentRunContext, LlmModelTarget)} 落属性即可。
 * </p>
 *
 * @author pcy
 * @since 2026/10/14
 */
public final class LlmModelTargetAttributes {

    /** 运行级 LLM 提供商覆盖（cy.ai.providers 的 key） */
    public static final AttributeKey<String> LLM_PROVIDER =
            new AttributeKey<>("runtime.llm.provider", String.class);

    /** 运行级 LLM 模型覆盖（null = 提供商默认模型） */
    public static final AttributeKey<String> LLM_MODEL =
            new AttributeKey<>("runtime.llm.model", String.class);

    private LlmModelTargetAttributes() {
    }

    /**
     * 从运行上下文解析模型目标（供 AiFacadeLlmInvoker 模型目标解析器委托；无覆盖返回 null）。
     *
     * @param ctx 运行上下文
     * @return 模型目标；无覆盖为 null
     */
    public static LlmModelTarget resolve(AgentRunContext ctx) {
        String provider = ctx.getAttr(LLM_PROVIDER);
        String model = ctx.getAttr(LLM_MODEL);
        return provider == null && model == null ? null : new LlmModelTarget(provider, model);
    }

    /**
     * 将模型目标写入运行上下文属性（目标为 null 或空目标时不写）。
     *
     * @param ctx    运行上下文
     * @param target 模型目标（可空）
     */
    public static void applyTo(AgentRunContext ctx, LlmModelTarget target) {
        if (target == null || target.isEmpty()) {
            return;
        }
        ctx.putAttr(LLM_PROVIDER, target.provider());
        ctx.putAttr(LLM_MODEL, target.model());
    }
}
