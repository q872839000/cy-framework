// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.llm.impl;


import com.cy.orrery.agent.runtime.constant.RuntimeErrorCode;
import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.RunCancellationException;
import com.cy.orrery.agent.runtime.llm.LlmCallOptions;
import com.cy.orrery.agent.runtime.llm.LlmInvoker;
import com.cy.orrery.agent.runtime.llm.LlmRetryPolicy;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 带重试的 LLM 调用装饰器。
 * <p>
 * 按 {@link LlmRetryPolicy} 对传输级瞬时失败做有界指数退避重试；
 * 重试耗尽返回最后一次失败结果（stopHint 语义保持，由引擎决定终止路径）。
 * 每次重试前检查取消信号（挂起 / 退避期间取消的运行不再发起重试）。
 * 退避等待被中断（运行取消的典型路径）时恢复中断标记并立即返回取消失败
 * （LLM_CANCELLED），不再发起下一跳。
 * 装饰顺序建议：RetryingLlmInvoker 在外、宿主适配器在内、
 * TruncationContinuationDecorator 最内（截断是确定性结局不参与重试）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class RetryingLlmInvoker implements LlmInvoker {

    private static final Logger log = LoggerFactory.getLogger(RetryingLlmInvoker.class);

    private final LlmInvoker delegate;
    private final LlmRetryPolicy policy;

    /**
     * 构造重试装饰器。
     *
     * @param delegate 被包装的 LLM 调用 SPI
     * @param policy   重试策略
     */
    public RetryingLlmInvoker(LlmInvoker delegate, LlmRetryPolicy policy) {
        this.delegate = delegate;
        this.policy = policy;
    }

    @Override
    public List<Map<String, Object>> toolSpecs() {
        return delegate.toolSpecs();
    }

    @Override
    public LlmRoundResult invoke(AgentRunContext ctx, int roundIndex, List<Map<String, Object>> toolSpecs) {
        return invoke(ctx, roundIndex, toolSpecs, LlmCallOptions.NONE);
    }

    @Override
    public LlmRoundResult invoke(AgentRunContext ctx, int roundIndex,
                                  List<Map<String, Object>> toolSpecs, LlmCallOptions options) {
        // options 必须透传给被包装者：只覆写三参签名时，四参调用经接口 default
        // 回退到三参——结构化输出约束（response_format/tool_choice）会静默失效
        LlmRoundResult result = delegate.invoke(ctx, roundIndex, toolSpecs, options);
        int attempt = 0;
        while (result.failed() && policy.shouldRetry(result) && attempt < policy.maxRetries()) {
            if (ctx.isCancelled()) {
                throw new RunCancellationException(ctx.getRunId(), "llm_retry_" + roundIndex);
            }
            attempt++;
            long backoff = policy.backoffMillis(attempt);
            log.warn("LLM 瞬时失败，退避重试 runId={} round={} attempt={} backoffMs={} error={}",
                    ctx.getRunId(), roundIndex, attempt, backoff, result.errorMessage());
            if (!sleep(backoff)) {
                // 退避被中断：恢复中断标记后按取消失败立即返回（继续等 / 重试无意义）
                return LlmRoundResult.fail(RuntimeErrorCode.LLM_CANCELLED.code(),
                        RuntimeErrorCode.LLM_CANCELLED.format(), null, false);
            }
            result = delegate.invoke(ctx, roundIndex, toolSpecs, options);
        }
        return result;
    }

    /**
     * 可中断睡眠。
     *
     * @param millis 毫秒
     * @return true 正常睡满；false 被中断（中断标记已恢复）
     */
    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
