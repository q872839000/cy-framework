// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.runner;


import com.cy.orrery.agent.runtime.checkpoint.CheckpointManager;
import com.cy.orrery.agent.runtime.checkpoint.Durability;
import com.cy.orrery.agent.runtime.dag.DagConfig;
import com.cy.orrery.agent.runtime.dag.DagAttributes;
import com.cy.orrery.agent.runtime.dag.DagEngine;
import com.cy.orrery.agent.runtime.dag.DagNode;
import com.cy.orrery.agent.runtime.dag.DagNodeHandler;
import com.cy.orrery.agent.runtime.dag.DagOutcome;
import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.CancellationRegistry;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.context.RunCancellationException;
import com.cy.orrery.agent.runtime.event.Events;
import com.cy.orrery.agent.runtime.event.RuntimeEventBus;
import com.cy.orrery.agent.runtime.event.RuntimeEventBusFactory;
import com.cy.orrery.agent.runtime.hitl.HitlAttributes;
import com.cy.orrery.agent.runtime.hitl.HitlCoordinator;
import com.cy.orrery.agent.runtime.hitl.SuspendedState;
import com.cy.orrery.agent.runtime.hitl.SuspensionReason;
import com.cy.orrery.agent.runtime.loop.AgentStop;
import com.cy.orrery.agent.runtime.loop.LoopAttributes;
import com.cy.orrery.agent.runtime.loop.LoopOutcome;
import com.cy.orrery.agent.runtime.loop.LoopRuntimeSpec;
import com.cy.orrery.agent.runtime.loop.ReactLoopEngine;
import com.cy.orrery.agent.runtime.pipeline.PipelineAttributes;
import com.cy.orrery.agent.runtime.pipeline.PipelineConfig;
import com.cy.orrery.agent.runtime.pipeline.PipelineEngine;
import com.cy.orrery.agent.runtime.pipeline.PipelineOutcome;
import com.cy.orrery.agent.runtime.pipeline.StageHandlerRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 运行门面：框架级的一体化运行编排（库 → 框架的分水岭）。
 * <p>
 * 引擎（{@link ReactLoopEngine} / {@link PipelineEngine}）只负责单次 run 的编排；
 * 本门面把「一次运行的完整生命周期」做成框架托管：
 * <ol>
 *     <li><b>运行上下文生命周期</b>：{@link #startContext} 创建（总线经工厂挂载桥接 /
 *         观测监听器、注册取消、发 RUN_STARTED）；{@link #restoreContext} 自检查点恢复
 *         并自动重注册挂起的审批门（HITL 跨重启续跑）；</li>
 *     <li><b>检查点自动落盘</b>：按 {@link Durability} 档位——SYNC / ASYNC 经总线监听器
 *         在 STEP_FINISHED 触发（快照在引擎线程内同步完成，ASYNC 仅写存储走后台，
 *         提交与写入失败均 ERROR 日志 + 可选失败钩子计数），任何档位在挂起与终态都落盘；</li>
 *     <li><b>挂起持久化</b>：审批（NEEDS_APPROVAL）/ 澄清（NEEDS_CLARIFICATION）/
 *         阶段等待（AWAITING）时组装 {@link SuspendedState} 契约并入检查点，
 *         进程重启 / 换副本后可恢复；</li>
 *     <li><b>统一终态事件</b>：RUN_COMPLETED / RUN_FAILED / CANCELLED 由门面发射
 *         （引擎不发 run 级事件），终态后清理检查点与跨挂起段遥测累计；</li>
 *     <li><b>审批续跑</b>：{@link #awaitApprovalsAndResume} 把「等待决定 → 注入合成
 *         回执 → 重入引擎（确定性重放暂缓调用）」串成一步，等待期间响应取消。</li>
 * </ol>
 * 宿主装配：引擎实例（宿主按领域 SPI 构造）随方法传入，门面本身无状态可单例。
 * 落盘职责（自动落盘监听 / 异步落盘执行器 / 在途计数）由协作器
 * {@link CheckpointAutosave} 托管，门面不再使用时经 {@link #close} 级联释放。
 * </p>
 *
 * @author pcy
 * @since 2026/08/27
 */
public class AgentRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private final RuntimeEventBusFactory busFactory;
    private final CheckpointManager checkpoints;
    private final HitlCoordinator hitl;
    private final CancellationRegistry cancellations;
    private final CheckpointAutosave autosave;

    /** 活跃运行计数（startContext / restoreContext +1、finishTerminal -1；观测 gauge 源）。 */
    private final AtomicLong activeRuns = new AtomicLong();

    /** 引擎未提供更细错误码时的运行失败兜底码。 */
    private static final String DEFAULT_RUN_FAILED_CODE = "AGENT_RUN_FAILED";

    /**
     * 聚合依赖（构造参数 &gt;5 的封装载体，AGENTS.md §6）。
     *
     * @param busFactory           事件总线工厂（桥接 / 观测监听器挂载点）
     * @param checkpoints          检查点管理器
     * @param hitl                 HITL 协调器
     * @param cancellations        取消注册表（可空，表示仅总线可取消）
     * @param durability           检查点落盘档位（null 用 ASYNC）
     * @param asyncSaveFailureHook 异步落盘失败钩子（可空；观测计数用，日志必打）
     */
    public record Dependencies(RuntimeEventBusFactory busFactory,
                               CheckpointManager checkpoints,
                               HitlCoordinator hitl,
                               CancellationRegistry cancellations,
                               Durability durability,
                               Consumer<Throwable> asyncSaveFailureHook) {
    }

    /**
     * 构造运行门面（聚合依赖形态）。
     *
     * @param deps 聚合依赖
     */
    public AgentRunner(Dependencies deps) {
        this.busFactory = deps.busFactory();
        this.checkpoints = deps.checkpoints();
        this.hitl = deps.hitl();
        this.cancellations = deps.cancellations();
        this.autosave = new CheckpointAutosave(deps.checkpoints(), deps.durability(),
                deps.asyncSaveFailureHook());
    }

    /**
     * 构造运行门面。
     *
     * @param busFactory    事件总线工厂（桥接 / 观测监听器挂载点）
     * @param checkpoints   检查点管理器
     * @param hitl          HITL 协调器
     * @param cancellations 取消注册表（可空，表示仅总线可取消）
     * @param durability    检查点落盘档位
     */
    public AgentRunner(RuntimeEventBusFactory busFactory, CheckpointManager checkpoints,
                       HitlCoordinator hitl, CancellationRegistry cancellations,
                       Durability durability) {
        this(new Dependencies(busFactory, checkpoints, hitl, cancellations, durability, null));
    }

    // ========================= 上下文生命周期 =========================

    /**
     * 创建运行上下文（新运行）：总线挂载全局监听器、注册取消、发 RUN_STARTED。
     *
     * @param runId           运行 ID
     * @param initialMessages 初始消息（system / user 等；可空）
     * @return 运行上下文
     */
    public AgentRunContext startContext(String runId, List<LoopMessage> initialMessages) {
        RuntimeEventBus bus = busFactory.createBus(runId);
        return startContext(runId, bus, initialMessages);
    }

    /**
     * 创建运行上下文（新运行，复用宿主已装配的事件总线）：注册取消、挂载检查点自动落盘、发 RUN_STARTED。
     * <p>
     * 供需要把 runtime 引擎事件桥接到自有 wire 通道的宿主使用——宿主在调用前先建总线
     * 并挂好桥接监听器（如 scenario 经 AgentEventBusManager 建总线 + 挂 AgentEventBridge），
     * 再把它传入，保证引擎事件与宿主领域事件汇入同一条总线。
     * </p>
     *
     * @param runId           运行 ID
     * @param bus             宿主已装配的事件总线（桥接 / 观测监听器已挂载）
     * @param initialMessages 初始消息（system / user 等；可空）
     * @return 运行上下文
     */
    public AgentRunContext startContext(String runId, RuntimeEventBus bus, List<LoopMessage> initialMessages) {
        // 先构造上下文再注册取消：构造抛错时注册表不残留条目
        AgentRunContext ctx = new AgentRunContext(runId, bus, cancellations, initialMessages);
        if (cancellations != null) {
            cancellations.register(runId);
        }
        autosave.attach(ctx);
        activeRuns.incrementAndGet();
        Events.runStarted(bus);
        return ctx;
    }

    /**
     * 自检查点恢复运行上下文（挂起 / 崩溃后续跑）。
     * <p>
     * 恢复成功后自动重注册挂起的审批门（{@code HitlCoordinator.resumeGates}）——
     * 进程重启后原门内等待者已丢，不重注册则后续 decide 落空、等待直接超时。
     * </p>
     *
     * @param runId 运行 ID
     * @return 恢复的上下文；无检查点为 empty
     */
    public Optional<AgentRunContext> restoreContext(String runId) {
        RuntimeEventBus bus = busFactory.createBus(runId);
        Optional<AgentRunContext> restored = checkpoints.restore(runId, bus, cancellations);
        restored.ifPresent(ctx -> {
            autosave.attach(ctx);
            activeRuns.incrementAndGet();
            hitl.resumeGates(ctx);
        });
        return restored;
    }

    /**
     * 当前活跃运行数（startContext / restoreContext 起、终态止；观测 gauge 源）。
     *
     * @return 活跃运行数
     */
    public long activeRuns() {
        return activeRuns.get();
    }

    // ========================= ReAct 循环运行 =========================

    /**
     * 运行 ReAct 循环（框架托管：终态事件、挂起持久化、终态清理）。
     *
     * @param engine 循环引擎（宿主构造，无状态可复用）
     * @param ctx    运行上下文（{@link #startContext} / {@link #restoreContext} 产出）
     * @param spec   运行规格
     * @return 循环结果
     */
    public LoopOutcome runReact(ReactLoopEngine engine, AgentRunContext ctx, LoopRuntimeSpec spec) {
        return runReact(engine, ctx, spec, null);
    }

    /**
     * 运行 ReAct 循环（带宿主扩展位，随挂起态契约持久化）。
     *
     * @param engine     循环引擎
     * @param ctx        运行上下文
     * @param spec       运行规格
     * @param hostExtras 宿主扩展位（如意图快照；可空）
     * @return 循环结果
     */
    public LoopOutcome runReact(ReactLoopEngine engine, AgentRunContext ctx, LoopRuntimeSpec spec,
                                Map<String, Object> hostExtras) {
        try {
            LoopOutcome outcome = engine.run(ctx, spec);
            settleLoopOutcome(ctx, outcome, hostExtras);
            return outcome;
        } catch (RunCancellationException e) {
            Events.cancelled(ctx.getBus(), cancellationReason(ctx), e.phase());
            finishTerminal(ctx);
            throw e;
        } catch (Exception e) {
            // 非取消的未捕获异常（工具/LLM 适配器/宿主钩子穿透）：必须收敛终态——
            // 无 RUN_FAILED 则事件消费者收到悬空流；不 finishTerminal 则
            // 活跃计数与取消注册泄漏
            log.error("ReAct 运行未捕获异常，收敛 RUN_FAILED runId={}", ctx.getRunId(), e);
            Events.runFailed(ctx.getBus(), DEFAULT_RUN_FAILED_CODE,
                    e.getMessage() == null ? e.toString() : e.getMessage(), true);
            finishTerminal(ctx);
            throw e;
        }
    }

    /**
     * 审批挂起后的等待与续跑：等待全部决定 → 注入合成回执 → 重入引擎
     * （引擎自动确定性重放暂缓调用）。
     * <p>
     * 等待前与等待中（协调器逐单检查）均响应取消：已取消直接抛
     * {@link RunCancellationException} 并按 CANCELLED 终态收敛。
     * </p>
     *
     * @param engine        循环引擎
     * @param ctx           运行上下文（挂起时持久化后恢复 / 原持有均可）
     * @param spec          运行规格
     * @param timeoutMillis 单个审批的等待超时毫秒（超时按拒绝兜底）
     * @return 续跑后的循环结果
     * @throws InterruptedException 等待线程被中断
     * @throws RunCancellationException 运行已取消
     */
    public LoopOutcome awaitApprovalsAndResume(ReactLoopEngine engine, AgentRunContext ctx,
                                               LoopRuntimeSpec spec, long timeoutMillis)
            throws InterruptedException {
        if (ctx.isCancelled()) {
            throw new RunCancellationException(ctx.getRunId(), "hitl_await_entry");
        }
        // 续跑再挂起时沿用原挂起态的宿主扩展位（记忆归属 / 会话定位跨多次审批不衰减；
        // awaitAndApply 会清挂起态，须先取出）
        SuspendedState priorState = HitlAttributes.suspendedState(ctx);
        Map<String, Object> priorExtras = priorState != null ? priorState.hostExtras() : null;
        try {
            hitl.awaitAndApply(ctx, timeoutMillis, spec.hooks());
        } catch (RunCancellationException e) {
            Events.cancelled(ctx.getBus(), cancellationReason(ctx), e.phase());
            finishTerminal(ctx);
            throw e;
        }
        return runReact(engine, ctx, spec, priorExtras);
    }

    // ========================= 阶段链运行 =========================

    /**
     * 运行阶段链（框架托管：Awaiting 挂起持久化、终态事件、终态清理）。
     *
     * @param engine       阶段链引擎
     * @param ctx          运行上下文
     * @param initialStage 起始阶段标识
     * @param registry     阶段处理器注册表
     * @param config       链配置
     * @return 链执行结果
     */
    public PipelineOutcome runPipeline(PipelineEngine engine, AgentRunContext ctx,
                                       String initialStage, StageHandlerRegistry registry,
                                       PipelineConfig config) {
        return runPipeline(engine, ctx, initialStage, registry, config, null);
    }

    /**
     * 运行阶段链（带宿主扩展位）。
     *
     * @param engine       阶段链引擎
     * @param ctx          运行上下文
     * @param initialStage 起始阶段标识
     * @param registry     阶段处理器注册表
     * @param config       链配置
     * @param hostExtras   宿主扩展位（可空）
     * @return 链执行结果
     */
    public PipelineOutcome runPipeline(PipelineEngine engine, AgentRunContext ctx,
                                       String initialStage, StageHandlerRegistry registry,
                                       PipelineConfig config, Map<String, Object> hostExtras) {
        try {
            PipelineOutcome outcome = engine.run(ctx, initialStage, registry, config);
            settlePipelineOutcome(ctx, outcome, hostExtras);
            return outcome;
        } catch (RunCancellationException e) {
            Events.cancelled(ctx.getBus(), cancellationReason(ctx), e.phase());
            finishTerminal(ctx);
            throw e;
        } catch (Exception e) {
            // 与 runReact 同口径：未捕获异常收敛 RUN_FAILED + 终态清理后原样上抛
            log.error("阶段链运行未捕获异常，收敛 RUN_FAILED runId={}", ctx.getRunId(), e);
            Events.runFailed(ctx.getBus(), DEFAULT_RUN_FAILED_CODE,
                    e.getMessage() == null ? e.toString() : e.getMessage(), true);
            finishTerminal(ctx);
            throw e;
        }
    }

    // ========================= DAG 运行 =========================

    /**
     * 运行 DAG（框架托管：Awaiting 挂起持久化、终态事件、终态清理）。
     *
     * @param engine  DAG 引擎
     * @param ctx     运行上下文
     * @param nodes   节点列表
     * @param handler 节点处理器（宿主注入节点语义）
     * @param config  执行配置
     * @param <N>     节点类型
     * @return DAG 执行结果
     */
    public <N extends DagNode> DagOutcome runDag(DagEngine engine, AgentRunContext ctx,
                                                 List<N> nodes, DagNodeHandler<N> handler,
                                                 DagConfig config) {
        return runDag(engine, ctx, nodes, handler, config, null);
    }

    /**
     * 运行 DAG（带宿主扩展位，随挂起态契约持久化）。
     *
     * @param engine     DAG 引擎
     * @param ctx        运行上下文
     * @param nodes      节点列表
     * @param handler    节点处理器（宿主注入节点语义）
     * @param config     执行配置
     * @param hostExtras 宿主扩展位（可空）
     * @param <N>        节点类型
     * @return DAG 执行结果
     */
    public <N extends DagNode> DagOutcome runDag(DagEngine engine, AgentRunContext ctx,
                                                 List<N> nodes, DagNodeHandler<N> handler,
                                                 DagConfig config, Map<String, Object> hostExtras) {
        try {
            DagOutcome outcome = engine.run(ctx, nodes, handler, config);
            settleDagOutcome(ctx, outcome, hostExtras);
            return outcome;
        } catch (RunCancellationException e) {
            Events.cancelled(ctx.getBus(), cancellationReason(ctx), e.phase());
            finishTerminal(ctx);
            throw e;
        } catch (Exception e) {
            // 与 runReact 同口径：未捕获异常收敛 RUN_FAILED + 终态清理后原样上抛
            log.error("DAG 运行未捕获异常，收敛 RUN_FAILED runId={}", ctx.getRunId(), e);
            Events.runFailed(ctx.getBus(), DEFAULT_RUN_FAILED_CODE,
                    e.getMessage() == null ? e.toString() : e.getMessage(), true);
            finishTerminal(ctx);
            throw e;
        }
    }

    /**
     * 取取消原因（总线原因优先，缺省用异常消息）。
     *
     * @param ctx 运行上下文
     * @return 取消原因
     */
    private String cancellationReason(AgentRunContext ctx) {
        String reason = ctx.getBus().cancellationReason();
        return reason == null ? "运行被取消" : reason;
    }

    // ========================= 内部：结算 =========================

    /**
     * ReAct 循环结果结算：挂起持久化 / 终态事件与清理。
     *
     * @param ctx        运行上下文
     * @param outcome    循环结果
     * @param hostExtras 宿主扩展位
     */
    private void settleLoopOutcome(AgentRunContext ctx, LoopOutcome outcome,
                                   Map<String, Object> hostExtras) {
        AgentStop stop = outcome.stop();
        if (stop == AgentStop.NEEDS_APPROVAL) {
            hitl.suspend(ctx, LoopAttributes.pendingApprovals(ctx), hostExtras);
            autosave.saveNow(ctx, "approval_suspend");
            return;
        }
        if (stop == AgentStop.NEEDS_CLARIFICATION || stop == AgentStop.NEEDS_PLAN_CONFIRMATION) {
            SuspendedState state = new SuspendedState(ctx.getRunId(),
                    stop == AgentStop.NEEDS_CLARIFICATION
                            ? SuspensionReason.NEEDS_CLARIFICATION
                            : SuspensionReason.NEEDS_PLAN_CONFIRMATION,
                    List.of(), null, hostExtras, System.currentTimeMillis());
            HitlAttributes.stashSuspendedState(ctx, state);
            autosave.saveNow(ctx, "clarification_suspend");
            return;
        }
        LoopAttributes.clearPendingApprovals(ctx);
        if (stop == AgentStop.FINAL_ANSWER || stop == AgentStop.MAX_STEPS) {
            // MAX_STEPS 归入"完成"是有意口径：轮次耗尽时模型已给出当前最佳收敛
            // （区别于 OUTPUT_TRUNCATED 的显式失败）；下游监控如需区分看 stop 字段。
            // 终态遥测取跨挂起段累计值：挂起不是终点，轮次 / 调用 / token 为多段之和
            Events.runCompleted(ctx.getBus(), Map.of(
                    "stop", stop.name(),
                    "rounds", LoopAttributes.totalRounds(ctx),
                    "toolCalls", LoopAttributes.totalToolCalls(ctx),
                    "promptTokens", LoopAttributes.totalPromptTokens(ctx),
                    "completionTokens", LoopAttributes.totalCompletionTokens(ctx)));
        } else {
            Events.runFailed(ctx.getBus(),
                    outcome.failedCode() == null ? DEFAULT_RUN_FAILED_CODE : outcome.failedCode(),
                    outcome.errorMsg(), outcome.failedRetryable());
        }
        finishTerminal(ctx);
    }

    /**
     * 阶段链结果结算：Awaiting 挂起持久化 / 终态事件与清理。
     *
     * @param ctx        运行上下文
     * @param outcome    链执行结果
     * @param hostExtras 宿主扩展位
     */
    private void settlePipelineOutcome(AgentRunContext ctx, PipelineOutcome outcome,
                                       Map<String, Object> hostExtras) {
        switch (outcome.status()) {
            case AWAITING -> {
                // 挂起阶段标识用 awaitingStage（恢复链路按它 lookup）；
                // message 是给人看的等待说明——此前混用会导致恢复时 lookup 不到阶段
                String stage = outcome.awaitingStage() != null
                        ? outcome.awaitingStage() : outcome.message();
                SuspendedState state = SuspendedState.ofStage(ctx.getRunId(), stage, hostExtras);
                HitlAttributes.stashSuspendedState(ctx, state);
                autosave.saveNow(ctx, "stage_awaiting");
            }
            case DONE, ASK_USER_DECISION -> finishPipelineCompleted(ctx, outcome);
            case SWITCH_ENGINE -> {
                // 引擎切换非终态：宿主接管后续编排；落盘保现场
                autosave.saveNow(ctx, "switch_engine");
            }
            default -> {
                Events.runFailed(ctx.getBus(),
                        outcome.code() == null ? DEFAULT_RUN_FAILED_CODE : outcome.code(),
                        outcome.message(), outcome.retryable());
                finishTerminal(ctx);
            }
        }
    }

    /**
     * DAG 结果结算：Awaiting 挂起持久化 / 终态事件与清理。
     *
     * @param ctx        运行上下文
     * @param outcome    DAG 执行结果
     * @param hostExtras 宿主扩展位
     */
    private void settleDagOutcome(AgentRunContext ctx, DagOutcome outcome,
                                  Map<String, Object> hostExtras) {
        switch (outcome.status()) {
            case AWAITING -> {
                // 挂起键即恢复链路的 lookup 键（如人工门的 approvalKey）
                SuspendedState state = SuspendedState.ofStage(ctx.getRunId(),
                        outcome.awaitingKey(), hostExtras);
                HitlAttributes.stashSuspendedState(ctx, state);
                autosave.saveNow(ctx, "dag_awaiting");
            }
            case DONE, PARTIAL -> {
                // PARTIAL 归入完成口径：失败分支已尽力隔离（下游级联放弃），
                // 监控如需区分看 status 字段与 dropped 计数
                Events.runCompleted(ctx.getBus(), Map.of(
                        "stop", outcome.status().name(),
                        "executed", outcome.executedCount(),
                        "dropped", outcome.droppedCount()));
                finishTerminal(ctx);
            }
            default -> {
                Events.runFailed(ctx.getBus(),
                        outcome.failedCode() == null ? DEFAULT_RUN_FAILED_CODE : outcome.failedCode(),
                        outcome.message(), outcome.retryable());
                finishTerminal(ctx);
            }
        }
    }

    /**
     * 阶段链正常终态（Done / 用户裁定）结算：完成事件 + 终态清理。
     *
     * @param ctx     运行上下文
     * @param outcome 链执行结果
     */
    private void finishPipelineCompleted(AgentRunContext ctx, PipelineOutcome outcome) {
        Events.runCompleted(ctx.getBus(), Map.of(
                "stop", outcome.status().name(),
                "transitions", outcome.transitions(),
                "rollbacks", outcome.rollbacks()));
        finishTerminal(ctx);
    }

    /**
     * 终态收尾：注销取消、清理检查点与跨挂起段遥测累计（终态运行不可续）。
     * <p>
     * 档位语义对齐 {@link Durability}：EXIT 档运行期不按步骤落盘（仅挂起路径落盘），
     * 终态不补落盘而是直接清理——终态结果经事件分发，检查点无续跑价值。
     * </p>
     *
     * @param ctx 运行上下文
     */
    private void finishTerminal(AgentRunContext ctx) {
        activeRuns.decrementAndGet();
        LoopAttributes.clearRunTotals(ctx);
        PipelineAttributes.clear(ctx);
        DagAttributes.clear(ctx);
        autosave.awaitInflight(ctx.getRunId());
        if (cancellations != null) {
            cancellations.unregister(ctx.getRunId());
        }
        checkpoints.delete(ctx.getRunId());
    }

    /**
     * 结束一个经 {@link #startContext} 创建、但由宿主自行编排（不经 {@code runReact} /
     * {@code runPipeline} / {@code runDag}）的运行：回收生命周期簿记与检查点。
     * <p>
     * 宿主自带编排与终态事件发射权（如 scenario 的 LoopFinalizer 统一发终态），
     * 本方法只做资源收尾（活跃计数 / 取消注销 / 检查点清理 / 在途落盘等待），
     * 不发任何事件。与 {@link #discardContext} 的差异：本方法用于正常进入过引擎的运行，
     * 会清理检查点；{@code discardContext} 用于探测性恢复后未进入引擎的上下文。
     * </p>
     *
     * @param ctx 运行上下文
     */
    public void finishRun(AgentRunContext ctx) {
        if (ctx == null) {
            return;
        }
        finishTerminal(ctx);
    }

    /**
     * 弃置一个恢复出的运行上下文（探测性恢复发现无可续跑内容时调用）。
     * <p>
     * 语义仅回收 {@link #restoreContext} 的簿记（并发计数），不触碰检查点、
     * 不发事件——调用方确认该上下文不会进入引擎后使用。
     * </p>
     *
     * @param ctx 恢复出的运行上下文
     */
    public void discardContext(AgentRunContext ctx) {
        if (ctx == null) {
            return;
        }
        activeRuns.decrementAndGet();
        if (cancellations != null) {
            cancellations.unregister(ctx.getRunId());
        }
    }

    /**
     * 关闭异步落盘执行器（宿主容器销毁门面 Bean 时调用）。
     */
    @Override
    public void close() {
        autosave.close();
    }
}
