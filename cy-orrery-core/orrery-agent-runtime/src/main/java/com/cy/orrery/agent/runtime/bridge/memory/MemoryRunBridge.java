// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.bridge.memory;

import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.memory.api.MemoryCapabilities;
import com.cy.orrery.memory.api.MemoryScope;
import com.cy.orrery.memory.assemble.MemoryContextAssembler;
import com.cy.orrery.memory.context.ChatMessage;
import com.cy.orrery.memory.context.ContextManager;
import com.cy.orrery.memory.context.ContextSession;
import com.cy.orrery.memory.episodic.EpisodeService;
import com.cy.orrery.memory.profile.ProfileItem;
import com.cy.orrery.memory.profile.ProfileService;
import com.cy.orrery.memory.semantic.ConsolidationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆运行桥（memory 组件与 agent-runtime 的 run 生命周期接线，组合式）。
 * <p>
 * 宿主在 AgentRunner 门面之外按生命周期调用（不侵入引擎）：
 * <ul>
 *   <li><b>run 启动</b>：{@link #buildStartupInjection} 装配注入文本
 *       （画像段 + 相关记忆段 + 会话摘要段，预算内定界包裹），调用方追加到
 *       system 消息；</li>
 *   <li><b>run 结束</b>：{@link #onRunCompleted} 异步巩固——L2 事实巩固走
 *       <b>仅用户侧</b>文本（assistant 复述是记忆投影，不得再当证据形成回声回路），
 *       L1 情景卡片走全量文本。后台虚拟线程执行，绝不阻塞 run 返回。</li>
 * </ul>
 * 组件能力未装配时各方法安全降级，不抛异常。
 * </p>
 *
 * @author pcy
 */
public class MemoryRunBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryRunBridge.class);

    /** 巩固任务并发上限（巩固含多次 LLM 调用，无界并发会打爆下游 LLM 配额）。 */
    private static final int MAX_CONCURRENT_CONSOLIDATIONS = 8;

    /** 优雅关闭窗口（秒）。 */
    private static final int SHUTDOWN_GRACE_SECONDS = 30;

    /** 注入装配器。 */
    private final MemoryContextAssembler assembler;

    /** 上下文管理器（取会话滚动摘要）。 */
    private final ContextManager contextManager;

    /** L2 巩固管线（可空 = 未装配）。 */
    private final ConsolidationService consolidationService;

    /** L1 情景服务（可空 = 未装配）。 */
    private final EpisodeService episodeService;

    /** 画像服务（可空 = 未装配或无维度注册）。 */
    private final ProfileService profileService;

    /** 巩固执行器（虚拟线程）。 */
    private final ExecutorService consolidationExecutor;

    /** 巩固并发闸门（虚拟线程无池上限，信号量是有界性的唯一来源）。 */
    private final Semaphore consolidationPermits = new Semaphore(MAX_CONCURRENT_CONSOLIDATIONS);

    /**
     * 聚合依赖。
     *
     * @param assembler            注入装配器
     * @param contextManager       上下文管理器
     * @param consolidationService L2 巩固管线（可空）
     * @param episodeService       L1 情景服务（可空）
     * @param profileService       画像服务（可空）
     * @param capabilities         能力视图（可空 = 不校验）
     */
    public record Dependencies(MemoryContextAssembler assembler, ContextManager contextManager,
                               ConsolidationService consolidationService,
                               EpisodeService episodeService, ProfileService profileService,
                               MemoryCapabilities capabilities) {

        /** 五参构造（无画像接线）。 */
        public Dependencies(MemoryContextAssembler assembler, ContextManager contextManager,
                            ConsolidationService consolidationService,
                            EpisodeService episodeService, MemoryCapabilities capabilities) {
            this(assembler, contextManager, consolidationService, episodeService, null, capabilities);
        }
    }

    /**
     * 构造记忆运行桥（各子系统服务可空，未装配时对应动作安全跳过）。
     *
     * @param deps 聚合依赖
     */
    public MemoryRunBridge(Dependencies deps) {
        this.assembler = deps.assembler();
        this.contextManager = deps.contextManager();
        this.consolidationService = deps.consolidationService();
        this.episodeService = deps.episodeService();
        this.profileService = deps.profileService();
        this.consolidationExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("memory-consolidation-", 0).factory());
        MemoryCapabilities caps = deps.capabilities();
        if (caps != null) {
            if (consolidationService != null && !caps.isSemanticAvailable()) {
                log.warn("L2 语义记忆未装配——onRunCompleted 的事实巩固将被跳过"
                        + "（开启 cy.orrery.memory.semantic.enabled）");
            }
            if (episodeService != null && !caps.isEpisodicAvailable()) {
                log.warn("L1 情景记忆未装配——onRunCompleted 的情景沉淀将被跳过"
                        + "（开启 cy.orrery.memory.episodic.enabled）");
            }
            if (profileService != null && !caps.isProfileAvailable()) {
                log.warn("画像未装配——onRunCompleted 的画像提取将被跳过"
                        + "（开启 cy.orrery.memory.profile.enabled 并注册 ProfileDimensionProvider）");
            }
        }
    }

    /**
     * run 启动注入文本（调用方追加到 system 消息尾部）。
     *
     * @param scope          记忆归属（user/agent 隔离）
     * @param sessionId      会话 ID（取滚动摘要）
     * @param latestUserText 当前用户输入（相关记忆检索词）
     * @return 注入文本；无内容（记忆为空/能力未装配）为 empty
     */
    public Optional<String> buildStartupInjection(MemoryScope scope, String sessionId,
                                                  String latestUserText) {
        if (assembler == null) {
            return Optional.empty();
        }
        try {
            String summary = contextManager == null || sessionId == null
                    ? null : contextManager.peekSummary(sessionId);
            String text = assembler.assemble(new MemoryContextAssembler.AssembleRequest(
                    scope, sessionId, latestUserText, summary));
            return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (RuntimeException e) {
            log.warn("记忆启动注入失败（跳过，不影响运行）", e);
            return Optional.empty();
        }
    }

    /**
     * run 结束：异步巩固（L2 提取-决策 + L1 情景卡片）。
     * <p>
     * 幂等性由调用方保证（每个 runId 至多一次）；本方法立即返回。
     * </p>
     *
     * @param scope     巩固目标归属
     * @param sessionId 会话 ID（情景卡片定位）
     * @param runId     运行 ID（证据定位）
     * @param messages  本次 run 的消息历史
     */
    public void onRunCompleted(MemoryScope scope, String sessionId, String runId,
                               List<LoopMessage> messages) {
        if (messages == null || messages.isEmpty()
                || (consolidationService == null && episodeService == null)) {
            return;
        }
        List<LoopMessage> snapshot = List.copyOf(messages);
        String evidence = sessionId + "#" + runId;
        try {
            // execute（非 submit）：submit 把任务包成 FutureTask，Error 会被存进
            // 无人读取的 Future 里静默消失——execute + 全量 catch 保证严重异常可见
            consolidationExecutor.execute(() -> {
                consolidationPermits.acquireUninterruptibly();
                try {
                    consolidate(scope, sessionId, evidence, snapshot);
                } catch (RuntimeException e) {
                    log.warn("记忆巩固任务异常（丢弃本轮）: runId={}", runId, e);
                } catch (Throwable t) {
                    log.error("记忆巩固任务严重异常（丢弃本轮）: runId={}", runId, t);
                } finally {
                    consolidationPermits.release();
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("记忆巩固任务提交被拒（桥已关闭，丢弃本轮）: runId={}", runId);
        }
    }

    /** 巩固编排（各动作相互隔离：单个失败不拖垮其余）。 */
    private void consolidate(MemoryScope scope, String sessionId, String evidence,
                             List<LoopMessage> messages) {
        maintainContextWindow(sessionId, messages);
        String userTranscript = String.join("\n", userOnlyLines(messages));
        if (consolidationService != null && !userTranscript.isBlank()) {
            try {
                consolidationService.consolidate(scope, userTranscript, evidence);
            } catch (RuntimeException e) {
                log.warn("L2 事实巩固失败（跳过，不影响其余巩固动作）: {}", evidence, e);
            }
        }
        if (episodeService != null && sessionId != null) {
            try {
                episodeService.settle(sessionId, scope, String.join("\n", toTranscriptLines(messages)));
            } catch (RuntimeException e) {
                log.warn("L1 情景沉淀失败（跳过）: sessionId={}", sessionId, e);
            }
        }
        if (profileService != null && !userTranscript.isBlank()) {
            try {
                ProfileItem.OwnerType ownerType = switch (scope.type()) {
                    case USER -> ProfileItem.OwnerType.USER;
                    case AGENT -> ProfileItem.OwnerType.AGENT;
                    default -> null;
                };
                if (ownerType != null) {
                    profileService.extractAndUpdate(ownerType, scope.tenantId(),
                            scope.ownerId(), userTranscript);
                }
            } catch (RuntimeException e) {
                log.warn("画像提取失败（跳过）: owner={}", scope.ownerId(), e);
            }
        }
    }

    /**
     * L0 窗口维护：本轮 user/assistant 消息进窗口，触发一次窗口守望
     * （超阈值折叠/驱逐+摘要），快照写回供下一轮注入的摘要段使用。
     *
     * @param sessionId 会话 ID（null 跳过）
     * @param messages  本轮消息
     */
    private void maintainContextWindow(String sessionId, List<LoopMessage> messages) {
        if (contextManager == null || sessionId == null || !contextManager.windowPersistent()) {
            return;
        }
        List<ChatMessage> turns = new ArrayList<>();
        for (LoopMessage m : messages) {
            if (m == null || m.content() == null || m.content().isBlank()) {
                continue;
            }
            if (LoopMessage.ROLE_USER.equals(m.role()) || LoopMessage.ROLE_ASSISTANT.equals(m.role())) {
                turns.add(ChatMessage.of(m.role(), m.content()));
            }
        }
        if (turns.isEmpty()) {
            return;
        }
        try (ContextSession session = contextManager.open(sessionId)) {
            session.append(turns);
            // 触发窗口守望（折叠/驱逐+摘要），返回值不使用——守望副作用与快照持久化才是目的
            session.prepareForLlm();
        } catch (RuntimeException e) {
            log.warn("上下文窗口维护失败（跳过，不影响其余巩固动作）: sessionId={}", sessionId, e);
        }
    }

    /** 全量对话行（user/assistant；system/tool 不进摘要与卡片）。 */
    private List<String> toTranscriptLines(List<LoopMessage> messages) {
        List<String> lines = new ArrayList<>();
        for (LoopMessage m : messages) {
            if (m == null || m.content() == null || m.content().isBlank()) {
                continue;
            }
            String speaker = switch (m.role() == null ? "" : m.role()) {
                case LoopMessage.ROLE_USER -> "用户";
                case LoopMessage.ROLE_ASSISTANT -> "助手";
                default -> null;
            };
            if (speaker != null) {
                lines.add(speaker + "：" + m.content().strip());
            }
        }
        return lines;
    }

    /** 用户侧行（事实提取证据唯一来源：assistant 复述与 system 注入均排除）。 */
    private List<String> userOnlyLines(List<LoopMessage> messages) {
        List<String> lines = new ArrayList<>();
        for (LoopMessage m : messages) {
            if (m != null && LoopMessage.ROLE_USER.equals(m.role())
                    && m.content() != null && !m.content().isBlank()) {
                lines.add("用户：" + m.content().strip());
            }
        }
        return lines;
    }

    @Override
    public void close() {
        consolidationExecutor.shutdown();
        try {
            if (!consolidationExecutor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                List<Runnable> dropped = consolidationExecutor.shutdownNow();
                // 强杀的未完成任务必须留名：巩固半途而废不可静默
                log.warn("记忆巩固任务未在 {}s 内完成，强制关闭（丢弃 {} 个未完成任务）",
                        SHUTDOWN_GRACE_SECONDS, dropped.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            List<Runnable> dropped = consolidationExecutor.shutdownNow();
            log.warn("记忆巩固执行器关闭被中断（丢弃 {} 个未完成任务）", dropped.size());
        }
    }
}
