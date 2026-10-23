// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.hitl.impl;

import com.cy.orrery.agent.runtime.hitl.ApprovalDecision;
import com.cy.orrery.agent.runtime.hitl.ApprovalGate;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 进程内审批等待门默认实现。
 * <p>
 * 每个审批单一个 {@link CompletableFuture}；decide 完成 future 唤醒等待者，
 * abort 以取消异常完成（等待方按拒绝兜底）。重复决定 / 未知审批单返回 false。
 * 注册表条目在等待方取走结果（决定 / 超时 / 中止兜底 / 中断）或被 {@link #abort}
 * 时清理，决定先于等待到达也不影响取回（Future 完成态可立即读出）。
 * </p>
 * <p>
 * 生命周期边界：本实现的条目清理依赖「等待方到达 / abort」——宿主挂起后
 * 既不等待也不中止的场景（会话被放弃）条目会残留，长期驻留进程应使用
 * Redis 审批门（决定存储兜底 + TTL）或自行周期调用 {@link #abort}。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class InMemoryApprovalGate implements ApprovalGate {

    private final Map<String, CompletableFuture<ApprovalDecision>> pending = new ConcurrentHashMap<>();

    @Override
    public void register(String approvalId) {
        pending.putIfAbsent(Objects.requireNonNull(approvalId, "approvalId"),
                new CompletableFuture<>());
    }

    @Override
    public ApprovalDecision awaitDecision(String approvalId, long timeoutMillis)
            throws InterruptedException {
        CompletableFuture<ApprovalDecision> future = pending.get(approvalId);
        if (future == null) {
            return ApprovalDecision.timedOut(approvalId);
        }
        try {
            ApprovalDecision decision = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            // 取走结果即清理，防条目泄漏；重复等待按未知审批单超时兜底。
            pending.remove(approvalId, future);
            return decision;
        } catch (TimeoutException e) {
            pending.remove(approvalId, future);
            return ApprovalDecision.timedOut(approvalId);
        } catch (InterruptedException e) {
            // 等待线程被中断：同样清理条目（悬挂的 future 无等待方，后续可重注册）
            pending.remove(approvalId, future);
            throw e;
        } catch (ExecutionException | CancellationException e) {
            // abort 以异常完成（取消异常以 CancellationException 直接抛出、
            // 其它异常包装为 ExecutionException）：均按拒绝兜底，不向等待线程穿透——
            // 中止语义是"运行释放"，等待方拿到拒绝决定走统一收敛路径。
            pending.remove(approvalId, future);
            return ApprovalDecision.aborted(approvalId);
        }
    }

    @Override
    public boolean decide(String approvalId, boolean approved, String decidedBy, String comment) {
        CompletableFuture<ApprovalDecision> future = pending.get(approvalId);
        if (future == null || future.isDone()) {
            return false;
        }
        ApprovalDecision decision = approved
                ? ApprovalDecision.approve(approvalId, decidedBy)
                : ApprovalDecision.reject(approvalId, decidedBy, comment);
        return future.complete(decision);
    }

    @Override
    public void abort(String approvalId) {
        CompletableFuture<ApprovalDecision> future = pending.remove(approvalId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 当前未决审批单数量（可观测性 / 泄漏防护验证用）。
     *
     * @return 未决数量
     */
    public int pendingCount() {
        return pending.size();
    }
}
