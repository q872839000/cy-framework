// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.AttributeKey;
import com.cy.orrery.agent.runtime.context.LoopToolCall;
import com.cy.orrery.agent.runtime.event.Events;
import com.cy.orrery.agent.runtime.llm.LlmInvoker;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 小步走检测器：连续 N 轮「≤1 个工具调用且 completion token 低于阈值」时注入合并提示。
 * <p>
 * 检测逻辑领域无关，注入文案经 {@link LoopDomainSpec#microStepMergeNudge} 供给；
 * 提示经 {@code replaceOrAddUserMessage} 哨兵前缀替换（重复提示不累积）。
 * 计数存于运行上下文属性，跨轮保持、任意非小步轮清零。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class MicroStepDetector {

    private static final Logger log = LoggerFactory.getLogger(MicroStepDetector.class);

    /** 小步走阈值：连续 N 轮触发提示注入。 */
    private final int microStepThreshold;

    /** 单轮被认为「小步」的 completion token 上限。 */
    private final int microStepTokenLimit;

    /** 小步走连续计数器键名（检查点序列化器按此键做强类型注册）。 */
    public static final String COUNTER_KEY_NAME = "microStepCounter";

    /** 小步走连续计数器键。 */
    private static final AttributeKey<Integer> MICRO_STEP_COUNTER =
            new AttributeKey<>(COUNTER_KEY_NAME, Integer.class);

    /** 小步合并提示的哨兵前缀（用于撤换上一条、避免累积）。 */
    public static final String NUDGE_SENTINEL_PREFIX = "⚠️ 提示：你已经连续";

    /**
     * 构造检测器（默认阈值：连续 3 轮、单轮 100 token）。
     */
    public MicroStepDetector() {
        this(3, 100);
    }

    /**
     * 构造检测器（阈值可按模型 / 场景调整）。
     *
     * @param microStepThreshold 连续多少轮小步触发提示注入（≥1）
     * @param microStepTokenLimit 单轮被认为「小步」的 completion token 上限（≥1）
     */
    public MicroStepDetector(int microStepThreshold, int microStepTokenLimit) {
        this.microStepThreshold = Math.max(1, microStepThreshold);
        this.microStepTokenLimit = Math.max(1, microStepTokenLimit);
    }

    /**
     * 小步走检测与提示注入。
     *
     * @param ctx            运行上下文
     * @param calls          本轮 tool_calls
     * @param roundResult    本轮 LLM 归一化结果
     * @param nudgeText      提示文案供给（入参为连续轮数）
     */
    public void nudgeIfMicroStepping(AgentRunContext ctx, List<LoopToolCall> calls,
                                     LlmInvoker.LlmRoundResult roundResult,
                                     IntFunction<String> nudgeText) {
        boolean isMicro = calls.size() <= 1 && roundResult.completionTokens() < microStepTokenLimit;
        int counter = ctx.getAttr(MICRO_STEP_COUNTER, 0);
        counter = isMicro ? counter + 1 : 0;
        if (counter >= microStepThreshold) {
            ctx.replaceOrAddUserMessage(NUDGE_SENTINEL_PREFIX, nudgeText.apply(counter));
            Events.systemTrace(ctx.getBus(), "agent.micro_step",
                    "连续小步动作，注入合并提示", Map.of("consecutive", counter));
            log.info("Micro-step nudge injected runId={} consecutiveCount={}",
                    ctx.getRunId(), counter);
            counter = 0;
        }
        ctx.putAttr(MICRO_STEP_COUNTER, counter);
    }
}
