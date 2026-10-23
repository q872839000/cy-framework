// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.event;

/**
 * 调试级别
 * <p>
 * 控制 Agent 运行期间是否向客户端发送调试频道事件。具体放行规则由 {@link EventVisibility} 统一裁决。
 * 用户频道事件（StageChanged/Progress/Milestone/Clarification/ApprovalRequired/BusinessError/Final/Cancelled/RunFailed，
 * 以及步骤结构事件 StepStarted/StepFinished 与所有块 BlockStart/Delta/End——含正文 text 块与思维链 reasoning
 * 块，后者按"展示思考"决策走用户频道、前端默认折叠）始终发送；
 * 调试频道事件（ToolCallStart/End、LlmCall、RawLlmChunk、SystemTrace）根据当前级别过滤。
 * </p>
 * <p>
 * 级别语义（由小到大，包含关系）：
 * <ul>
 *     <li>{@link #OFF}：仅用户频道事件，调试事件全部丢弃</li>
 *     <li>{@link #MINIMAL}：额外包含 ToolCallStart / ToolCallEnd，方便前端显示"正在调用 XXX 工具"</li>
 *     <li>{@link #VERBOSE}：额外包含 LlmCall（模型用量与耗时统计），适合开发时查看调用情况。
 *         注：模型思维链（reasoning 块）按"展示思考"走用户频道、前端默认折叠，不受此级别门控</li>
 *     <li>{@link #TRACE}：额外包含 RawLlmChunk / SystemTrace，用于完整回放和深度排障</li>
 * </ul>
 * </p>
 *
 * @author pcy
 * @date 2026/04/23
 */
public enum DebugLevel {

    /** 仅用户频道事件 */
    OFF,

    /** 额外包含工具调用起止 */
    MINIMAL,

    /** 额外包含 LLM 调用和 Token 增量 */
    VERBOSE,

    /** 额外包含原始 Chunk 和系统追踪 */
    TRACE;

    /**
     * 宽松解析调试级别字符串。
     * <p>
     * 大小写不敏感；null 或无法识别的值默认返回 {@link #OFF}。
     * </p>
     *
     * @param value 字符串值（如 "verbose"、"TRACE"）
     * @return 调试级别枚举，解析失败返回 {@link #OFF}
     */
    public static DebugLevel parse(String value) {
        if (value == null) {
            return OFF;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return OFF;
        }
        try {
            return DebugLevel.valueOf(v.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return OFF;
        }
    }

    /**
     * 判断当前级别是否覆盖给定级别。
     * <p>
     * 用于事件过滤：当某个调试事件要求的最低级别为 {@code other} 时，
     * 若 {@code this.includes(other) == true} 则允许通过。
     * </p>
     *
     * @param other 目标级别
     * @return 是否包含 {@code other} 所需的调试粒度
     */
    public boolean includes(DebugLevel other) {
        return this.ordinal() >= other.ordinal();
    }
}
