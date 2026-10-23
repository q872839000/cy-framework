// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.runner;


import com.cy.orrery.agent.runtime.checkpoint.Checkpoint;
import com.cy.orrery.agent.runtime.checkpoint.CheckpointManager;
import com.cy.orrery.agent.runtime.checkpoint.Durability;
import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.event.RuntimeEventType;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 检查点自动落盘协作器（{@link AgentRunner} 的落盘职责抽离）。
 * <p>
 * 托管：SYNC / ASYNC 档位的 STEP_FINISHED 触发落盘（快照在引擎线程内同步完成，
 * ASYNC 仅写存储走后台）、异步落盘在途计数（终态清理前等待归零，防迟到快照
 * 复活已删除的检查点）、挂起路径立即落盘、失败兜底日志与可选观测钩子。
 * 异步落盘执行器为虚拟线程，随 {@link #close} 释放。
 * </p>
 *
 * @author pcy
 * @since 2026/09/29
 */
final class CheckpointAutosave implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CheckpointAutosave.class);

    /** 终态等待在途异步落盘的上限（毫秒）：超时后继续清理并告警。 */
    private static final long ASYNC_SAVE_DRAIN_MILLIS = 5000L;

    /** 终态等待在途落盘的轮询间隔（毫秒）。 */
    private static final long ASYNC_SAVE_POLL_MILLIS = 20L;

    private final CheckpointManager checkpoints;
    private final Durability durability;

    /** 异步落盘失败钩子（可空：观测计数由宿主装配层注入，如 Micrometer 计数器）。 */
    private final Consumer<Throwable> asyncSaveFailureHook;

    private final ExecutorService asyncCheckpointExecutor;

    /** 在途异步落盘计数（runId → 计数）：终态清理前等待归零，防检查点复活。 */
    private final ConcurrentHashMap<String, AtomicInteger> inflightSaves = new ConcurrentHashMap<>();

    /**
     * 构造协作器。
     *
     * @param checkpoints          检查点管理器
     * @param durability           落盘档位（null 用 ASYNC）
     * @param asyncSaveFailureHook 异步落盘失败钩子（可空；观测计数用，日志必打）
     */
    CheckpointAutosave(CheckpointManager checkpoints, Durability durability,
                       Consumer<Throwable> asyncSaveFailureHook) {
        this.checkpoints = checkpoints;
        this.durability = durability == null ? Durability.ASYNC : durability;
        this.asyncSaveFailureHook = asyncSaveFailureHook;
        this.asyncCheckpointExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 挂载检查点自动落盘监听器（SYNC / ASYNC 档位在 STEP_FINISHED 触发）。
     *
     * @param ctx 运行上下文
     */
    void attach(AgentRunContext ctx) {
        if (durability == Durability.EXIT) {
            return;
        }
        ctx.getBus().addListener(event -> {
            if (event.type() != RuntimeEventType.STEP_FINISHED) {
                return;
            }
            Checkpoint snapshot = checkpoints.snapshot(ctx);
            if (durability == Durability.SYNC) {
                checkpoints.save(snapshot);
            } else {
                submitAsyncSave(ctx, snapshot);
            }
        });
    }

    /**
     * 立即保存检查点（挂起路径；任何 durability 档位都落盘）。
     *
     * @param ctx    运行上下文
     * @param reason 落盘原因（日志用）
     * @return 已保存的检查点
     */
    Checkpoint saveNow(AgentRunContext ctx, String reason) {
        Checkpoint checkpoint = checkpoints.save(ctx);
        log.info("检查点落盘 runId={} revision={} reason={}", ctx.getRunId(), checkpoint.revision(), reason);
        return checkpoint;
    }

    /**
     * 等待该 run 在途异步落盘归零（终态 delete 不得先于未落盘快照——
     * 否则迟到的后台写入会"复活"已清理的检查点）。
     *
     * @param runId 运行 ID
     */
    void awaitInflight(String runId) {
        AtomicInteger inflight = inflightSaves.get(runId);
        if (inflight == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + ASYNC_SAVE_DRAIN_MILLIS;
        while (inflight.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(ASYNC_SAVE_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (inflight.get() > 0) {
            log.warn("run 终态时仍有 {} 个在途异步快照未落盘（继续清理检查点）runId={}",
                    inflight.get(), runId);
        }
    }

    /**
     * 提交异步落盘任务（提交与执行失败均兜底：ERROR 日志必打、可选失败钩子计数——
     * 后台线程的异常若不兜底会被执行器静默吞掉，落盘失败完全不可见）。
     * 记录在途计数：终态清理等待归零，防迟到快照复活已删除的检查点。
     *
     * @param ctx      运行上下文
     * @param snapshot 快照（引擎线程内已完成）
     */
    private void submitAsyncSave(AgentRunContext ctx, Checkpoint snapshot) {
        AtomicInteger inflight = inflightSaves.computeIfAbsent(ctx.getRunId(),
                k -> new AtomicInteger());
        inflight.incrementAndGet();
        try {
            asyncCheckpointExecutor.execute(() -> {
                try {
                    checkpoints.save(snapshot);
                } catch (Throwable t) {
                    logAsyncSaveFailure(ctx, snapshot, t);
                } finally {
                    if (inflight.decrementAndGet() <= 0) {
                        inflightSaves.remove(ctx.getRunId(), inflight);
                    }
                }
            });
        } catch (Throwable t) {
            inflight.decrementAndGet();
            // 提交失败（close 后拒绝等）：快照已拍到内存，只能丢弃并告警
            logAsyncSaveFailure(ctx, snapshot, t);
        }
    }

    /**
     * 异步落盘失败日志 + 钩子。
     *
     * @param ctx      运行上下文
     * @param snapshot 失败的快照
     * @param failure  失败原因
     */
    private void logAsyncSaveFailure(AgentRunContext ctx, Checkpoint snapshot, Throwable failure) {
        log.error("异步检查点落盘失败 runId={} revision={}", ctx.getRunId(),
                snapshot.revision(), failure);
        if (asyncSaveFailureHook != null) {
            try {
                asyncSaveFailureHook.accept(failure);
            } catch (Throwable hookFailure) {
                log.warn("异步落盘失败钩子执行异常: {}", hookFailure.toString());
            }
        }
    }

    /**
     * 关闭异步落盘执行器。
     */
    @Override
    public void close() {
        asyncCheckpointExecutor.close();
    }
}
