// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.bridge.cyai;

/**
 * 运行级 LLM 调用目标（provider + 模型覆盖）
 * <p>
 * 由宿主的模型目标解析器按运行上下文求值：{@code provider} 非空白时按名调用
 * {@link com.cy.ai.AiFacade} 的指定 provider 重载，{@code model} 非空白时覆盖请求模型；
 * 均为空白等价于跟随平台默认（{@code cy.ai.default-provider} 与 provider 默认模型）。
 * </p>
 *
 * @param provider 目标 provider 名称（cy.ai.providers 的 key；可空 = 默认 provider）
 * @param model    模型覆盖（可空 = provider 默认模型）
 * @author pcy
 * @since 2026/10/02
 */
public record LlmModelTarget(String provider, String model) {

    /** 是否为空目标（两字段均空白，等价于未指定）。 */
    public boolean isEmpty() {
        return (provider == null || provider.isBlank()) && (model == null || model.isBlank());
    }
}
