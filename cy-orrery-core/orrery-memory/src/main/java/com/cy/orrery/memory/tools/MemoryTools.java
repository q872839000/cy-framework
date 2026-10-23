// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.tools;

import com.cy.orrery.memory.api.MemoryItem;
import com.cy.orrery.memory.api.MemoryKind;
import com.cy.orrery.memory.api.MemoryQuery;
import com.cy.orrery.memory.api.MemoryScope;
import com.cy.orrery.memory.api.Provenance;
import com.cy.orrery.memory.semantic.SemanticMemoryService;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 记忆工具门面（remember / recall / correct / forget，供 skill-runtime 绑定）。
 *
 * @author pcy
 */
public class MemoryTools {

    /** 语义记忆服务。 */
    private final SemanticMemoryService memoryService;

    /**
     * 构造记忆工具门面。
     *
     * @param memoryService 语义记忆服务
     */
    public MemoryTools(SemanticMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * 记住（热路径直写）。
     *
     * @param scope      归属
     * @param content    正文
     * @param kind       类型（fact/preference/experience）
     * @param importance 重要度 1-10
     * @param sourceRef  来源指针
     * @return 记忆 ID
     */
    public String remember(MemoryScope scope, String content, String kind, int importance,
                           String sourceRef) {
        MemoryItem saved = memoryService.remember(MemoryItem.builder()
                .scope(scope)
                .kind(MemoryKind.parse(kind))
                .content(content)
                .importance(importance)
                .sourceRef(sourceRef)
                .provenance(Provenance.userExplicit(scope.ownerId(), sourceRef))
                .build());
        return saved.getMemoryId();
    }

    /**
     * 语义召回（"id｜索引｜正文" 行，供 Agent 直接阅读）。
     *
     * @param queryText 查询文本
     * @param filter    过滤条件
     * @param topK      上限
     * @return 命中行
     */
    public List<String> recall(String queryText, MemoryQuery filter, int topK) {
        MemoryQuery q = filter == null ? MemoryQuery.builder().limit(topK).build() : filter;
        List<String> lines = new ArrayList<>();
        for (SemanticMemoryService.ScoredMemory hit : memoryService.recall(queryText, q)) {
            MemoryItem e = hit.entry();
            lines.add(e.getMemoryId() + "｜" + e.getAbstractText() + "｜" + e.getContent());
        }
        return lines;
    }

    /**
     * 更正记忆。
     *
     * @param memoryId   记忆 ID
     * @param newContent 新正文
     * @param actorId    操作者
     */
    public void correct(String memoryId, String newContent, String actorId) {
        correct(memoryId, newContent, null, actorId);
    }

    /**
     * 更正记忆（可显式指定新索引；null 时按正文重派生，保持索引与正文一致）。
     *
     * @param memoryId    记忆 ID
     * @param newContent  新正文
     * @param newAbstract 新索引（可空）
     * @param actorId     操作者
     */
    public void correct(String memoryId, String newContent, String newAbstract, String actorId) {
        memoryService.correct(memoryId, newContent, newAbstract,
                new Provenance(actorId, null, "Agent 工具更正", true));
    }

    /**
     * 遗忘（true = 物理擦除 / false = 作废）。
     *
     * @param memoryId 记忆 ID
     * @param hard     是否物理擦除
     * @param actorId  操作者
     * @param reason   依据
     */
    public void forget(String memoryId, boolean hard, String actorId, String reason) {
        if (hard) {
            memoryService.erase(memoryId, actorId, reason);
        } else {
            memoryService.invalidate(memoryId, reason);
        }
    }
}
