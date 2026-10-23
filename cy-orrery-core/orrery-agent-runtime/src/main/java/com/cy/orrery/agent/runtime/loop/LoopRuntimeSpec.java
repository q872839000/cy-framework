// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;

import com.cy.orrery.agent.runtime.harness.ContextGuard;
import com.cy.orrery.agent.runtime.llm.LlmInvoker;

/**
 * ReAct 循环运行规格：宿主每次运行前组装，随 run 调用传入。
 * <p>
 * 引擎本身无状态可单例复用；全部领域依赖（LLM 适配 / 领域钩子 / 上下文守望 /
 * 工具轮钩子）经本规格注入。{@code contextGuard} 与 {@code hooks} 可为 null
 * （null 分别表示跳过守望 / 使用空钩子）。
 * </p>
 *
 * @param config       循环配置
 * @param llmInvoker   LLM 调用 SPI
 * @param domainSpec   领域钩子
 * @param contextGuard 上下文守望（可空）
 * @param hooks        工具轮钩子（可空）
 * @author pcy
 * @since 2026/08/19
 */
public record LoopRuntimeSpec(
        LoopConfig config,
        LlmInvoker llmInvoker,
        LoopDomainSpec domainSpec,
        ContextGuard contextGuard,
        ToolRoundHooks hooks) {

    /**
     * 紧凑构造：hooks 空值规范化为空钩子——绕过 {@link #of} 工厂直接用
     * canonical 构造器时，null hooks 会在工具轮深处 NPE（难以定位）。
     */
    public LoopRuntimeSpec {
        if (hooks == null) {
            hooks = ToolRoundHooks.NO_OP;
        }
    }

    /**
     * 构造并规范化空依赖。
     *
     * @param config       循环配置
     * @param llmInvoker   LLM 调用 SPI
     * @param domainSpec   领域钩子
     * @param contextGuard 上下文守望（可空）
     * @param hooks        工具轮钩子（可空，规范化为空钩子）
     * @return 运行规格
     */
    public static LoopRuntimeSpec of(LoopConfig config, LlmInvoker llmInvoker,
                                     LoopDomainSpec domainSpec, ContextGuard contextGuard,
                                     ToolRoundHooks hooks) {
        return new LoopRuntimeSpec(config, llmInvoker, domainSpec, contextGuard, hooks);
    }
}
