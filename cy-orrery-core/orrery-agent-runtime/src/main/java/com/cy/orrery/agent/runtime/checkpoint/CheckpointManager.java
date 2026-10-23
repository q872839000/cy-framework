// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.checkpoint;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.CancellationRegistry;
import com.cy.orrery.agent.runtime.event.RuntimeEventBus;
import java.util.Optional;

/**
 * 检查点管理器：快照 / 恢复 / 清理的协调入口。
 * <p>
 * 快照：从 {@link AgentRunContext} 取消息历史 + 属性表 + 步骤游标 + 压缩边界，
 * revision 经 {@link CheckpointStore#nextRevision} 分配（存储实现保证单调：
 * 进程内计数器 / Redis INCR；单写者语义——同一 runId 同时只应有一个活跃写者，
 * 多写者 fencing 由宿主以 runId 粒度保证）。
 * 恢复：重建上下文（消息经构造传入，属性 / 游标 / 边界经恢复通道回填）。
 * 全部保存路径经 {@link CheckpointStore#saveIfNewer}（旧 revision 迟到不覆盖新）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/27
 */
public class CheckpointManager {

    private final CheckpointStore store;

    /**
     * 构造管理器。
     *
     * @param store 检查点存储
     */
    public CheckpointManager(CheckpointStore store) {
        this.store = store;
    }

    /**
     * 取当前上下文快照（revision 经存储分配递增，不落盘）。
     *
     * @param ctx 运行上下文
     * @return 检查点
     */
    public Checkpoint snapshot(AgentRunContext ctx) {
        return Checkpoint.of(ctx, store.nextRevision(ctx.getRunId()));
    }

    /**
     * 保存当前上下文快照。
     *
     * @param ctx 运行上下文
     * @return 已保存的检查点
     */
    public Checkpoint save(AgentRunContext ctx) {
        Checkpoint checkpoint = snapshot(ctx);
        store.saveIfNewer(checkpoint);
        return checkpoint;
    }

    /**
     * 保存给定检查点（异步落盘路径：快照已在引擎线程内同步完成，
     * 经 saveIfNewer fencing——后台乱序写入旧 revision 不覆盖新）。
     *
     * @param checkpoint 检查点
     */
    public void save(Checkpoint checkpoint) {
        store.saveIfNewer(checkpoint);
    }

    /**
     * 加载最新检查点。
     *
     * @param runId 运行 ID
     * @return 检查点；不存在为 empty
     */
    public Optional<Checkpoint> load(String runId) {
        return store.load(runId);
    }

    /**
     * 恢复运行上下文（挂起 / 崩溃后续跑）。
     * <p>
     * 事件总线与取消注册表不可序列化，由宿主按运行重新构造传入；
     * 消息历史 / 属性表 / 步骤游标 / 压缩边界自检查点还原。
     * </p>
     *
     * @param runId    运行 ID
     * @param bus      本运行新的事件总线
     * @param registry 取消注册表（可空）
     * @return 恢复的运行上下文；无检查点为 empty
     */
    public Optional<AgentRunContext> restore(String runId, RuntimeEventBus bus,
                                             CancellationRegistry registry) {
        return store.load(runId).map(checkpoint -> rebuildContext(checkpoint, bus, registry));
    }

    /**
     * 自检查点重建运行上下文（消息经构造传入，属性 / 游标 / 边界经恢复通道回填）。
     *
     * @param checkpoint 检查点
     * @param bus        本运行新的事件总线
     * @param registry   取消注册表（可空）
     * @return 恢复的运行上下文
     */
    private AgentRunContext rebuildContext(Checkpoint checkpoint, RuntimeEventBus bus,
                                           CancellationRegistry registry) {
        AgentRunContext ctx = new AgentRunContext(checkpoint.runId(), bus, registry,
                checkpoint.messages());
        checkpoint.attributes().forEach(ctx::restoreAttribute);
        ctx.restoreStepCursor(checkpoint.stepCursor());
        ctx.setCompactionBoundary(checkpoint.compactionBoundary());
        return ctx;
    }

    /**
     * 删除运行的检查点（终态清理）。
     *
     * @param runId 运行 ID
     */
    public void delete(String runId) {
        store.delete(runId);
    }
}
