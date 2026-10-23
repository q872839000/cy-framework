// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import java.util.Map;

/**
 * 工具执行 SPI。
 * <p>
 * 宿主把领域工具适配为本接口。引擎经该边界调用工具，不感知工具的实现形态
 * （本地 Bean / 远程技能 / MCP）。
 * </p>
 * <p>
 * <b>工具级事件契约</b>：TOOL_STARTED / TOOL_FINISHED 事件的发射权归工具 harness
 * （{@code GuardedToolInvoker}，经 {@code Events.toolStarted / toolFinished} 工厂发射）；
 * 实现（含宿主适配器）<b>禁止</b>再发射这两类事件，避免双发。
 * </p>
 * <p>
 * 错误约定：可自纠失败（参数错、候选歧义等）返回 {@link ToolCallResult#fail}；
 * 不可自纠失败（改参数重试也无意义）抛 {@link FatalToolCallException}，
 * 引擎按部分失败语义处理。其它未捕获异常按工具执行失败兜底。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public interface ToolInvoker {

    /**
     * 执行工具调用。
     *
     * @param callId    调用 ID
     * @param toolName  工具名
     * @param arguments 已解析参数
     * @param ctx       运行上下文
     * @return 执行结果
     * @throws FatalToolCallException 不可自纠的业务错误
     */
    ToolCallResult invoke(String callId, String toolName, Map<String, Object> arguments, AgentRunContext ctx);

    /**
     * 是否只读工具（无副作用，可安全并行 / 可在审批屏障前执行）。
     * <p>
     * 默认 false（保守）。标注只读且单轮数量 ≥2 的调用由
     * {@code LoopToolRoundEngine} 并行预执行（传入只读执行器时）；写调用保持
     * 串行与审批屏障语义。
     * </p>
     *
     * @param toolName 工具名
     * @return true 表示只读
     */
    default boolean isReadOnly(String toolName) {
        return false;
    }
}
