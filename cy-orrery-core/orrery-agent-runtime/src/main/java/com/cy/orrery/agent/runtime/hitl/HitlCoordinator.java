// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.hitl;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.RunCancellationException;
import com.cy.orrery.agent.runtime.loop.ApprovalRequest;
import com.cy.orrery.agent.runtime.loop.ToolRoundHooks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HITL 协调门面：把审批门与决定注入串成「挂起 → 等待 → 注入」一条龙。
 * <p>
 * 宿主外层运行器（AgentRunner 形态）的使用序列：
 * <pre>engine.run(...) 返回 NEEDS_APPROVAL →
 *   coordinator.suspend(ctx, pending, extras)   // 注册门 + 组装挂起态契约
 *   → 宿主持久化挂起态 + 发宿主 wire 审批事件
 *   → 决定到达（coordinator.decide / gate.decide 经审批回调端点投递）
 *   → coordinator.awaitAndApply(ctx, timeout, hooks)  // 等待 + 注入合成回执
 *   → 重新进入 engine.run(...)（引擎重放暂缓调用）</pre>
 * <b>跨重启恢复</b>：恢复侧先 {@link #resumeGates(AgentRunContext)} 重新注册审批门
 * （{@code AgentRunner.restoreContext} 已自动调用）；决定经 {@link #decide} 投递时
 * 先落 {@link ApprovalDecisionStore} 再唤醒门——决定先于等待到达（含进程重启后
 * 旧门已丢）由存储兜底，{@code awaitAndApply} 先查存储再等待。
 * 事件发射权：本类不发事件；wire 审批事件由宿主发射（与引擎审批钩子同一归属）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public final class HitlCoordinator {

    private static final Logger log = LoggerFactory.getLogger(HitlCoordinator.class);

    /** 取消信号轮询切片（毫秒）：审批阻塞等待期间的取消传播上限延迟。 */
    private static final long CANCEL_POLL_SLICE_MILLIS = 1000L;

    private final ApprovalGate gate;

    /** 决定持久化账本（可空：不落库时决定只在门内，先于等待到达会丢——生产建议装配）。 */
    private final ApprovalDecisionStore decisionStore;

    /**
     * 构造协调器。
     *
     * @param gate 审批等待门
     */
    public HitlCoordinator(ApprovalGate gate) {
        this(gate, null);
    }

    /**
     * 构造协调器（带决定存储）。
     *
     * @param gate           审批等待门
     * @param decisionStore  审批决定存储（可空；装配后「决定先于等待到达」不再丢）
     */
    public HitlCoordinator(ApprovalGate gate, ApprovalDecisionStore decisionStore) {
        this.gate = gate;
        this.decisionStore = decisionStore;
    }

    /**
     * 挂起：注册审批门 + 组装挂起态契约存入上下文属性。
     *
     * @param ctx     运行上下文
     * @param pending 待审批请求列表
     * @param hostExtras 宿主扩展位（随挂起态契约持久化；可空）
     * @return 挂起态契约（宿主取出连同自有元数据持久化）
     */
    public SuspendedState suspend(AgentRunContext ctx, List<ApprovalRequest> pending,
                                  Map<String, Object> hostExtras) {
        for (ApprovalRequest approval : pending) {
            gate.register(approval.approvalId());
        }
        SuspendedState state = SuspendedState.ofApproval(ctx.getRunId(), pending, hostExtras);
        HitlAttributes.stashSuspendedState(ctx, state);
        log.info("HITL 挂起 runId={} approvals={}", ctx.getRunId(), pending.size());
        return state;
    }

    /**
     * 跨重启恢复审批门：从上下文挂起态把待审批单重新注册进门。
     * <p>
     * 进程重启 / 换副本后原门内 future 已丢，不重注册则 decide 落空、
     * awaitDecision 直接超时。{@code AgentRunner.restoreContext} 恢复成功后
     * 自动调用本方法。
     * </p>
     *
     * @param ctx 恢复的运行上下文（含挂起态契约）
     * @return 恢复注册的审批单数量（无挂起态为 0）
     */
    public int resumeGates(AgentRunContext ctx) {
        return resumeGates(HitlAttributes.suspendedState(ctx));
    }

    /**
     * 跨重启恢复审批门（挂起态契约重载）。
     *
     * @param state 挂起态契约（可空，空时无操作）
     * @return 恢复注册的审批单数量
     */
    public int resumeGates(SuspendedState state) {
        if (state == null || state.pendingApprovals().isEmpty()) {
            return 0;
        }
        for (ApprovalRequest approval : state.pendingApprovals()) {
            gate.register(approval.approvalId());
        }
        log.info("HITL 审批门恢复注册 runId={} approvals={}",
                state.runId(), state.pendingApprovals().size());
        return state.pendingApprovals().size();
    }

    /**
     * 跨重启恢复审批门（审批单 ID 列表重载，宿主自持 ID 清单时使用）。
     *
     * @param approvalIds 审批单 ID 列表
     * @return 恢复注册的审批单数量
     */
    public int resumeGates(List<String> approvalIds) {
        if (approvalIds == null || approvalIds.isEmpty()) {
            return 0;
        }
        for (String approvalId : approvalIds) {
            gate.register(approvalId);
        }
        return approvalIds.size();
    }

    /**
     * 投递审批决定：先落决定存储（首决定不可覆盖）再唤醒门。
     * <p>
     * 审批回调端点应经本方法（而非直调 gate.decide）投递——即使等待方尚未
     * 注册门（决定先到 / 进程重启后旧门丢失），决定也已持久化，
     * 等待时经存储取回。
     * </p>
     *
     * @param approvalId 审批单 ID
     * @param approved   是否批准
     * @param decidedBy  决定人
     * @param comment    备注（可空）
     * @return true 表示已受理（已落库或已投递到等待中的门）；
     *         false = 无决定存储且门未在等待——决定实际丢失，调用方应感知
     */
    public boolean decide(String approvalId, boolean approved, String decidedBy, String comment) {
        ApprovalDecision decision = approved
                ? ApprovalDecision.approve(approvalId, decidedBy)
                : ApprovalDecision.reject(approvalId, decidedBy, comment);
        boolean persisted = false;
        if (decisionStore != null) {
            decisionStore.save(decision);
            persisted = true;
        }
        boolean delivered = gate.decide(approvalId, approved, decidedBy, comment);
        if (!delivered && !persisted) {
            // 无存储兜底且门未在等待：谎报"已受理"会让审批端点返回成功而决定凭空消失
            log.warn("HITL 决定未被受理（门未在等待且无决定存储）: approvalId={} approved={}",
                    approvalId, approved);
            return false;
        }
        if (!delivered) {
            log.info("HITL 决定落库但门侧未在等待（等待方到达后经存储取回）: "
                            + "approvalId={} approved={}", approvalId, approved);
        }
        return true;
    }

    /**
     * 等待全部审批决定并注入合成回执（超时按拒绝兜底）。
     * <p>
     * 等待顺序：先查决定存储（决定先于等待到达的直接采用）→ 再等门；
     * 等待按固定切片轮询——阻塞等待期间取消信号无法传播（门不感知取消），
     * 分片醒来检查取消，最坏一个切片的延迟收敛 CANCELLED 而非等满超时。
     * </p>
     *
     * @param ctx           运行上下文
     * @param timeoutMillis 单个审批的等待超时毫秒
     * @param hooks         领域钩子（回执文案供给）
     * @return 实际注入的决定列表（与挂起态中的待审批一一对应）
     * @throws InterruptedException 等待线程被中断
     * @throws RunCancellationException 运行已取消
     */
    public List<ApprovalDecision> awaitAndApply(AgentRunContext ctx, long timeoutMillis,
                                                ToolRoundHooks hooks) throws InterruptedException {
        SuspendedState state = HitlAttributes.suspendedState(ctx);
        if (state == null || state.pendingApprovals().isEmpty()) {
            return List.of();
        }
        List<ApprovalRequest> approvals = state.pendingApprovals();
        List<ApprovalDecision> decisions = new ArrayList<>(approvals.size());
        for (int i = 0; i < approvals.size(); i++) {
            ApprovalRequest approval = approvals.get(i);
            if (ctx.isCancelled()) {
                // 取消传播：已取消则中止本单及剩余全部审批单后抛取消
                abortRemaining(approvals, i);
                throw new RunCancellationException(ctx.getRunId(), "hitl_await_" + approval.approvalId());
            }
            ApprovalDecision decision;
            try {
                decision = awaitDecision(approval, timeoutMillis, ctx);
            } catch (RunCancellationException ex) {
                // 等待中途取消：同样要释放剩余审批单的门条目
                abortRemaining(approvals, i);
                throw ex;
            }
            decisions.add(decision);
            ApprovalDecisionApplier.apply(ctx, approval, decision, hooks);
            log.info("HITL 决定注入 runId={} approvalId={} approved={} timedOut={} fromStore={}",
                    ctx.getRunId(), approval.approvalId(), decision.approved(),
                    decision.timedOut(), decision == storeDecision(approval));
        }
        HitlAttributes.clearSuspendedState(ctx);
        return decisions;
    }

    /**
     * 等待单个决定：存储命中直接采用；否则按切片等门（切片间检查取消信号），
     * 取到真实决定（非超时兜底）时回写存储（决定可能经 gate.decide 直投未落库，
     * 回写保证幂等续读）。
     *
     * @param approval     审批请求
     * @param timeoutMillis 超时毫秒
     * @param ctx          运行上下文（取消信号）
     * @return 审批决定
     * @throws InterruptedException 等待被中断
     * @throws RunCancellationException 等待期间运行被取消
     */
    private ApprovalDecision awaitDecision(ApprovalRequest approval, long timeoutMillis,
                                           AgentRunContext ctx) throws InterruptedException {
        ApprovalDecision prior = storeDecision(approval);
        if (prior != null) {
            return prior;
        }
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            if (ctx.isCancelled()) {
                throw new RunCancellationException(ctx.getRunId(),
                        "hitl_await_" + approval.approvalId());
            }
            long remaining = deadline - System.currentTimeMillis();
            long slice = Math.min(CANCEL_POLL_SLICE_MILLIS, Math.max(remaining, 1));
            ApprovalDecision decision = gate.awaitDecision(approval.approvalId(), slice);
            if (!decision.timedOut()) {
                if (decisionStore != null) {
                    decisionStore.save(decision);
                }
                return decision;
            }
            if (System.currentTimeMillis() >= deadline) {
                // 整体超时：超时兜底决定（调用方按拒绝语义收敛）
                return decision;
            }
        }
    }

    /**
     * 取存储中的既有决定（未装配存储返回 null）。
     *
     * @param approval 审批请求
     * @return 已落库决定；无 / 未装配存储为 null
     */
    private ApprovalDecision storeDecision(ApprovalRequest approval) {
        if (decisionStore == null) {
            return null;
        }
        Optional<ApprovalDecision> prior = decisionStore.load(approval.approvalId());
        return prior.orElse(null);
    }

    /**
     * 中止指定下标起的剩余审批单（取消路径释放等待者，按拒绝语义兜底）。
     *
     * @param approvals 待审批列表
     * @param fromIndex 起始下标（含）
     */
    private void abortRemaining(List<ApprovalRequest> approvals, int fromIndex) {
        for (int i = fromIndex; i < approvals.size(); i++) {
            gate.abort(approvals.get(i).approvalId());
        }
        log.info("HITL 等待期间取消，中止剩余审批单 count={}", approvals.size() - fromIndex);
    }
}
