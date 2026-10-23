// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.checkpoint;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import java.util.List;
import java.util.Map;

/**
 * 运行检查点：一次运行的可序列化完整快照（对标 LangGraph 的 checkpoint）。
 * <p>
 * 快照内容 = 消息历史 + 属性表（业务动作日志 / 暂缓调用 / 挂起态 / 账本 /
 * 链计数等随属性携带）+ 步骤游标 + 压缩边界。属性表的键类型还原由
 * {@link CheckpointSerializer} 的键类型注册表负责：内核已知键（loop.* /
 * hitl.* / plan.* / pipeline.*）保证强类型往返；宿主自定义属性类型经
 * 注册表扩展，未注册的类型反序列化为通用 Map。
 * </p>
 *
 * @param runId              运行 ID
 * @param messages           消息历史快照
 * @param attributes         属性表快照（键为属性名）
 * @param stepCursor         步骤游标
 * @param compactionBoundary 上下文压缩边界
 * @param revision           修订号（每次保存递增，观测与 fencing 锚点）
 * @param updatedAtMillis    快照时间戳（毫秒）
 * @author pcy
 * @since 2026/08/27
 */
public record Checkpoint(
        String runId,
        List<LoopMessage> messages,
        Map<String, Object> attributes,
        int stepCursor,
        int compactionBoundary,
        long revision,
        long updatedAtMillis) {

    /**
     * 从运行上下文取快照。
     *
     * @param ctx      运行上下文
     * @param revision 修订号
     * @return 检查点
     */
    public static Checkpoint of(AgentRunContext ctx, long revision) {
        return new Checkpoint(ctx.getRunId(),
                List.copyOf(ctx.getMessages()),
                ctx.attributeSnapshot(),
                ctx.currentStepIndex(),
                ctx.getCompactionBoundary(),
                revision,
                System.currentTimeMillis());
    }
}
