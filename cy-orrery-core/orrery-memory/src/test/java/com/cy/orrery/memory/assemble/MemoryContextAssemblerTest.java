// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.assemble;

import com.cy.orrery.memory.api.MemoryItem;
import com.cy.orrery.memory.api.MemoryKind;
import com.cy.orrery.memory.api.MemoryScope;
import com.cy.orrery.memory.api.ScopeType;
import com.cy.orrery.memory.api.support.SnowflakeIdGenerator;
import com.cy.orrery.memory.profile.ProfileItem;
import com.cy.orrery.memory.profile.ProfileService;
import com.cy.orrery.memory.semantic.RetrievalConfig;
import com.cy.orrery.memory.semantic.SemanticMemoryService;
import com.cy.orrery.memory.support.FakeEmbeddingPort;
import com.cy.orrery.memory.support.FakeVectorIndex;
import com.cy.orrery.memory.support.InMemoryMemoryStore;
import com.cy.orrery.memory.support.InMemoryProfileStore;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注入装配器测试（三段装配 + 缺席段跳过 + 预算）。
 *
 * @author pcy
 */
class MemoryContextAssemblerTest {

    private static final MemoryScope USER_U1 = MemoryScope.ofUser(null, "u1");

    private SemanticMemoryService newSemantic() {
        return new SemanticMemoryService(new SemanticMemoryService.Dependencies(
                new InMemoryMemoryStore(), new FakeVectorIndex(), new FakeEmbeddingPort(),
                RetrievalConfig.defaults(), 1000, new SnowflakeIdGenerator(1), null, List.of(),
                Clock.systemUTC()));
    }

    private ProfileService newProfile() {
        return new ProfileService(new InMemoryProfileStore(),
                List.of(() -> List.of(
                        new com.cy.orrery.memory.profile.ProfileDimension(
                                "branch", "军种", "军种", false, 1))),
                null, new SnowflakeIdGenerator(2), Clock.systemUTC());
    }

    @Test
    void assemblesAllThreeSections() {
        SemanticMemoryService semantic = newSemantic();
        semantic.remember(MemoryItem.builder().scope(USER_U1).kind(MemoryKind.FACT)
                .content("用户偏好方案A").build());
        ProfileService profile = newProfile();
        profile.addExplicit(ProfileItem.OwnerType.USER, null, "u1", "branch", "陆军", null);

        MemoryContextAssembler assembler = new MemoryContextAssembler(
                MemoryContextAssembler.InjectionBudget.defaults(), profile, semantic, null);
        String text = assembler.assemble(new MemoryContextAssembler.AssembleRequest(
                USER_U1, "s1", "方案选择", "此前的会话摘要"));
        assertTrue(text.contains("<user-profile>"));
        assertTrue(text.contains("陆军"));
        assertTrue(text.contains("<relevant-memories>"));
        assertTrue(text.contains("用户偏好方案A"));
        assertTrue(text.contains("<session-summary>"));
        assertTrue(text.contains("此前的会话摘要"));
    }

    @Test
    void absentSubsystemsAreSkippedSilently() {
        MemoryContextAssembler assembler = new MemoryContextAssembler(
                MemoryContextAssembler.InjectionBudget.defaults(), null, null, null);
        String text = assembler.assemble(new MemoryContextAssembler.AssembleRequest(
                USER_U1, "s1", "查询", "只有摘要"));
        assertTrue(text.contains("<session-summary>"));
        assertFalse(text.contains("<user-profile>"));
        assertFalse(text.contains("<relevant-memories>"));
    }

    @Test
    void emptyContentReturnsNull() {
        MemoryContextAssembler assembler = new MemoryContextAssembler(
                MemoryContextAssembler.InjectionBudget.defaults(), null, null, null);
        assertNull(assembler.assemble(new MemoryContextAssembler.AssembleRequest(
                USER_U1, "s1", "查询", null)));
    }

    @Test
    void recalledSectionRespectsBudget() {
        SemanticMemoryService semantic = newSemantic();
        for (int i = 0; i < 10; i++) {
            semantic.remember(MemoryItem.builder().scope(USER_U1).kind(MemoryKind.FACT)
                    .content("记忆条目" + i + "内容内容内容内容内容内容内容内容内容内容").build());
        }
        MemoryContextAssembler assembler = new MemoryContextAssembler(
                new MemoryContextAssembler.InjectionBudget(2000, 500, 30, 500),
                null, semantic, null);
        String text = assembler.assemble(new MemoryContextAssembler.AssembleRequest(
                USER_U1, "s1", "内容", null));
        assertTrue(text.contains("<relevant-memories>"));
        // 预算 30 token → 只能放下很少行
        long lines = text.lines().filter(l -> l.startsWith("- ")).count();
        assertTrue(lines < 10, "预算截断生效");
    }

    @Test
    void sessionScopeHasNoProfileSection() {
        MemoryContextAssembler assembler = new MemoryContextAssembler(
                MemoryContextAssembler.InjectionBudget.defaults(), newProfile(), null, null);
        String text = assembler.assemble(new MemoryContextAssembler.AssembleRequest(
                MemoryScope.ofSession(null, "s9"), "s9", "查询", "摘要"));
        assertFalse(text.contains("<user-profile>"));
    }

    @Test
    void scopeQueryUsesCallerScope() {
        // scopeType=USER 但 ownerId 是别人 → 不应召回 u1 的记忆
        SemanticMemoryService semantic = newSemantic();
        semantic.remember(MemoryItem.builder().scope(USER_U1).kind(MemoryKind.FACT)
                .content("u1 的私有记忆").build());
        MemoryContextAssembler assembler = new MemoryContextAssembler(
                MemoryContextAssembler.InjectionBudget.defaults(), null, semantic, null);
        String text = assembler.assemble(new MemoryContextAssembler.AssembleRequest(
                MemoryScope.ofUser(null, "u2"), "s1", "私有记忆", null));
        assertNull(text);
    }
}
