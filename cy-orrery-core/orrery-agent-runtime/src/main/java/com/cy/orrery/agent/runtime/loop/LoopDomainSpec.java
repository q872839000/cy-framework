// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.llm.LlmInvoker;
import com.cy.orrery.agent.runtime.plan.LedgerProgressMatcher;
import java.util.List;

/**
 * 循环领域注入点：引擎托管编排，领域以钩子注入语义。
 * <p>
 * 引擎本身领域无关；无工具调用轮的校验结算、状态进展签名、终局结算语义、
 * 小步走与熔断文案由宿主实现本接口提供。所有钩子在引擎线程同步调用。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public interface LoopDomainSpec {

    /**
     * 无工具调用轮处理（pre-Final 校验、缺项提醒、终局判定等）。
     *
     * @param ctx   运行上下文
     * @param acc   循环累加器
     * @param round 本轮 LLM 归一化结果
     * @return true 表示循环应终止（终态语义已写入 acc）
     */
    boolean handleNoToolCallRound(AgentRunContext ctx, LoopAccumulator acc, LlmInvoker.LlmRoundResult round);

    /**
     * 终局结算（收敛熔断触发或轮次耗尽时调用）。
     * <p>
     * 默认空实现：宿主按需注入（如终局质量门裁决）。
     * </p>
     *
     * @param ctx    运行上下文
     * @param acc    循环累加器（终态语义写入此累加器）
     * @param reason 终局原因
     */
    default void settleTerminal(AgentRunContext ctx, LoopAccumulator acc, TerminalReason reason) {
    }

    /**
     * 领域状态进展签名：连续无工具调用轮之间比较，相同视为零进展（收敛熔断依据）。
     * <p>
     * 默认返回 null（不参与签名比较，退化为仅按业务动作日志增长判定进展）。
     * </p>
     *
     * @param ctx 运行上下文
     * @return 状态签名
     */
    default String stateSignature(AgentRunContext ctx) {
        return null;
    }

    /**
     * 退化熔断触发时的用户引导文案。
     *
     * @param actionLog 已生效业务动作日志
     * @return 引导文案
     */
    default String degradationFuseGuidance(List<String> actionLog) {
        return "刚才的操作连续没成功（多为参数或指令不匹配）。可以换种说法、给更明确的字段 / 取值，或把需求拆小后再试。";
    }

    /**
     * 退化熔断澄清事件的候选选项（展示层文案注入点，宿主按需覆写）。
     *
     * @return 候选选项
     */
    default List<String> degradationFuseOptions() {
        return List.of("换种说法重试", "查看当前状态");
    }

    /**
     * 小步走合并提示文案（连续 N 轮单个小步操作时注入）。
     * <p>
     * 默认文案以 {@link MicroStepDetector#NUDGE_SENTINEL_PREFIX} 开头——
     * 哨兵前缀是 {@code replaceOrAddUserMessage} 撤换上一条提示的定位依据，
     * ⚠️ 宿主覆写本方法时文案<b>必须</b>带该前缀（引用常量拼接），否则重复
     * 提示会逐条累积污染消息历史。
     * </p>
     *
     * @param consecutiveCount 连续小步轮数
     * @return 合并提示文案
     */
    default String microStepMergeNudge(int consecutiveCount) {
        return MicroStepDetector.NUDGE_SENTINEL_PREFIX + " " + consecutiveCount
                + " 轮只做单个小步骤操作。请评估后续是否可以把同类操作合并为一次批量调用（若工具支持批量参数），减少往返轮次。";
    }

    /**
     * 账本勾稽匹配器（任务账本的领域语义注入点）。
     * <p>
     * 返回 null（默认）表示宿主不启用账本：引擎跳过勾稽与提前收敛，零开销。
     * 宿主装配账本时（{@code PlanLedgerAttributes.stash}）应同时提供本匹配器。
     * </p>
     *
     * @return 匹配器；null 表示不启用账本勾稽
     */
    default LedgerProgressMatcher ledgerProgressMatcher() {
        return null;
    }

    /**
     * 账本全部条目到达终态时是否允许提前收敛（跳过剩余轮次直接终局结算）。
     * <p>
     * 默认 false（保守：领域需显式开启，且应在此完成终局校验——账本全完成
     * 不等于业务校验通过，判定权归宿主）。
     * </p>
     *
     * @param ctx 运行上下文
     * @return true 表示允许提前收敛
     */
    default boolean allowEarlySettlement(AgentRunContext ctx) {
        return false;
    }

    /**
     * 退化熔断触发时是否随熔断发澄清引导。
     * <p>
     * 默认 true（独立使用 runtime 时引导用户换说法重试）。宿主若把退化熔断作为
     * 纯失败终态（失败文案已含引导），覆写为 false 避免澄清 + 失败双输出。
     * </p>
     *
     * @return true 表示熔断时发澄清
     */
    default boolean degradationFuseEmitsClarification() {
        return true;
    }

    /**
     * 输出截断（finish_reason=length，含工具调用半截）到达引擎的处理钩子。
     * <p>
     * 默认返回 false：引擎按截断错误终态收敛（武装 {@code OUTPUT_TRUNCATED} 失败语义，
     * 丢弃本轮半截工具调用）。宿主若以「澄清驱动的可续跑挂起」语义处理截断
     * （暂存续跑态 + 发澄清，不武装失败终态），覆写本钩子返回 true——
     * 引擎随后不再写入 failedCode / errorMsg / finalText，由钩子全责。
     * </p>
     *
     * @param ctx   运行上下文
     * @param acc   循环累加器
     * @param round 本轮 LLM 归一化结果（截断时已聚合内容原样保留）
     * @return true 表示宿主已接管截断语义（引擎不再武装失败终态）
     */
    default boolean onOutputTruncated(AgentRunContext ctx, LoopAccumulator acc,
                                      LlmInvoker.LlmRoundResult round) {
        return false;
    }

    /** 终局原因。 */
    enum TerminalReason {

        /** 收敛熔断触发（连续零事实进展）。 */
        IDLE_FUSE,

        /** 轮次自然耗尽。 */
        ROUNDS_EXHAUSTED,

        /** 任务账本全部条目终态且领域允许提前收敛。 */
        LEDGER_COMPLETED
    }
}
