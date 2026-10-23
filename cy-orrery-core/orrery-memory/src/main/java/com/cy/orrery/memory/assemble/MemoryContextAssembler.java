// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.assemble;

import com.cy.orrery.memory.api.MemoryQuery;
import com.cy.orrery.memory.api.MemoryScope;
import com.cy.orrery.memory.context.TokenEstimator;
import com.cy.orrery.memory.episodic.EpisodeRecord;
import com.cy.orrery.memory.episodic.EpisodeService;
import com.cy.orrery.memory.profile.ProfileItem;
import com.cy.orrery.memory.profile.ProfileService;
import com.cy.orrery.memory.semantic.SemanticMemoryService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆上下文装配器（run 启动注入：画像段 + 相关记忆段 + 会话摘要段）。
 * <p>
 * 预算内定界包裹，未装配能力的段静默缺席；任何段失败不阻断其余段。
 * </p>
 *
 * @author pcy
 */
public class MemoryContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(MemoryContextAssembler.class);

    /** 情景召回条数（注入段最多引用的经历卡片数）。 */
    private static final int EPISODE_RECALL_LIMIT = 3;

    /** 总预算截断的字符估算倍率（token 预算 → 字符数，英文为主的混合文本按 4 字符/token 粗估）。 */
    private static final int CHARS_PER_TOKEN_LOOSE = 4;

    /** 摘要段截断的字符估算倍率（摘要以中文为主，按 2 字符/token 粗估，偏保守）。 */
    private static final int CHARS_PER_TOKEN_STRICT = 2;

    /** 注入预算配置。 */
    private final InjectionBudget budget;

    /** 画像服务（可空 = 未装配）。 */
    private final ProfileService profileService;

    /** 语义记忆服务（可空 = 未装配）。 */
    private final SemanticMemoryService semanticService;

    /** 情景记忆服务（可空 = 未装配）。 */
    private final EpisodeService episodeService;

    /**
     * 注入预算。
     *
     * @param tokenBudget          总预算
     * @param profileBudget        画像段预算
     * @param recalledBudget       相关记忆段预算
     * @param sessionSummaryBudget 会话摘要段预算
     */
    public record InjectionBudget(int tokenBudget, int profileBudget, int recalledBudget,
                                  int sessionSummaryBudget) {
        /** 默认预算。 */
        public static InjectionBudget defaults() {
            return new InjectionBudget(2000, 500, 1000, 500);
        }
    }

    /**
     * 装配请求。
     *
     * @param scope          运行归属（用户或 Agent）
     * @param sessionId      会话 ID
     * @param latestUserText 当前用户输入（相关记忆检索词）
     * @param sessionSummary 会话滚动摘要（来自 ContextSession；无则 null）
     */
    public record AssembleRequest(MemoryScope scope, String sessionId, String latestUserText,
                                  String sessionSummary) {
    }

    /**
     * 构造装配器（子系统服务可空，未装配能力的段静默缺席）。
     *
     * @param budget          注入预算（null 用默认值）
     * @param profileService  画像服务（可空）
     * @param semanticService 语义记忆服务（可空）
     * @param episodeService  情景记忆服务（可空）
     */
    public MemoryContextAssembler(InjectionBudget budget, ProfileService profileService,
                                  SemanticMemoryService semanticService,
                                  EpisodeService episodeService) {
        this.budget = budget == null ? InjectionBudget.defaults() : budget;
        this.profileService = profileService;
        this.semanticService = semanticService;
        this.episodeService = episodeService;
    }

    /**
     * 装配注入文本（追加到 system 消息尾部；无内容返回 null）。
     *
     * @param request 装配请求
     * @return 注入文本（定界标记分段包裹）
     */
    public String assemble(AssembleRequest request) {
        StringBuilder sb = new StringBuilder();
        appendSection(sb, "user-profile", profileSection(request));
        appendSection(sb, "relevant-memories", recalledSection(request));
        appendSection(sb, "session-summary", summarySection(request));
        String text = sb.toString().strip();
        if (text.isEmpty()) {
            return null;
        }
        // 总预算护栏：超限从尾部截断（段内预算已各自控制，这里是兜底）
        if (budget.tokenBudget() > 0 && TokenEstimator.estimate(text) > budget.tokenBudget()) {
            int keepChars = budget.tokenBudget() * CHARS_PER_TOKEN_LOOSE;
            text = text.length() <= keepChars ? text
                    : text.substring(0, Math.max(0, text.length() - (text.length() - keepChars)));
        }
        return text;
    }

    /** 画像段（未装配/无属主/空画像为 null）。 */
    private String profileSection(AssembleRequest request) {
        if (profileService == null || request.scope() == null) {
            return null;
        }
        ProfileItem.OwnerType ownerType = switch (request.scope().type()) {
            case USER -> ProfileItem.OwnerType.USER;
            case AGENT -> ProfileItem.OwnerType.AGENT;
            default -> null;
        };
        if (ownerType == null) {
            return null;
        }
        try {
            List<String> lines = profileService.render(ownerType, request.scope().tenantId(),
                    request.scope().ownerId(), budget.profileBudget());
            return lines.isEmpty() ? null : String.join("\n", lines);
        } catch (RuntimeException e) {
            log.warn("画像段渲染失败（跳过该段）: owner={}", request.scope().ownerId(), e);
            return null;
        }
    }

    /** 相关记忆段（L2 语义召回 + L1 情景召回，预算内逐行截断）。 */
    private String recalledSection(AssembleRequest request) {
        if (request.scope() == null || request.latestUserText() == null
                || request.latestUserText().isBlank()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        int used = 0;
        if (semanticService != null) {
            try {
                for (SemanticMemoryService.ScoredMemory hit : semanticService.recall(
                        request.latestUserText(), scopeFilter(request.scope()))) {
                    String line = "- " + hit.entry().getContent();
                    used += TokenEstimator.estimate(line);
                    if (budget.recalledBudget() > 0 && used > budget.recalledBudget()) {
                        break;
                    }
                    lines.add(line);
                }
            } catch (RuntimeException e) {
                log.warn("相关记忆召回失败（跳过）: owner={}", request.scope().ownerId(), e);
            }
        }
        if (episodeService != null && (budget.recalledBudget() <= 0
                || used < budget.recalledBudget())) {
            try {
                for (EpisodeRecord episode : episodeService.recall(
                        request.latestUserText(), request.scope(), EPISODE_RECALL_LIMIT)) {
                    String line = "- [上次经历] " + episode.getSummary();
                    used += TokenEstimator.estimate(line);
                    if (budget.recalledBudget() > 0 && used > budget.recalledBudget()) {
                        break;
                    }
                    lines.add(line);
                }
            } catch (RuntimeException e) {
                log.warn("情景召回失败（跳过）: owner={}", request.scope().ownerId(), e);
            }
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    /** 会话摘要段（无摘要为 null）。 */
    private String summarySection(AssembleRequest request) {
        String summary = request.sessionSummary();
        if (summary == null || summary.isBlank()) {
            return null;
        }
        // 段预算截断：保留尾部（最新信息优先）
        if (budget.sessionSummaryBudget() > 0
                && TokenEstimator.estimate(summary) > budget.sessionSummaryBudget()) {
            int keepChars = budget.sessionSummaryBudget() * CHARS_PER_TOKEN_STRICT;
            summary = "…" + summary.substring(Math.max(0, summary.length() - keepChars));
        }
        return summary;
    }

    /** scope → 召回过滤条件。 */
    private MemoryQuery scopeFilter(MemoryScope scope) {
        return MemoryQuery.builder()
                .tenantId(scope.tenantId()).scopeType(scope.type()).ownerId(scope.ownerId())
                .build();
    }

    /** 追加定界包裹的段。 */
    private void appendSection(StringBuilder sb, String tag, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        sb.append('<').append(tag).append('>').append('\n')
                .append(content.strip()).append('\n')
                .append("</").append(tag).append('>').append('\n');
    }
}
