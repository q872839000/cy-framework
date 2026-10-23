// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.semantic;

import com.cy.core.exception.CYException;
import com.cy.orrery.memory.api.MemoryItem;
import com.cy.orrery.memory.api.MemoryKind;
import com.cy.orrery.memory.api.MemoryQuery;
import com.cy.orrery.memory.api.MemoryScope;
import com.cy.orrery.memory.api.MemoryStatus;
import com.cy.orrery.memory.api.Provenance;
import com.cy.orrery.memory.api.ScopeType;
import com.cy.orrery.memory.api.support.SnowflakeIdGenerator;
import com.cy.orrery.memory.prompt.PromptResolver;
import com.cy.orrery.memory.support.FakeChatPort;
import com.cy.orrery.memory.support.FakeEmbeddingPort;
import com.cy.orrery.memory.support.FakeVectorIndex;
import com.cy.orrery.memory.support.InMemoryMemoryStore;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期语义记忆服务测试（向量唯一召回 + 乐观锁 + 配额 + 擦除 + 巩固管线）。
 *
 * @author pcy
 */
class SemanticMemoryServiceTest {

    private static final MemoryScope USER_U1 = MemoryScope.ofUser(null, "u1");

    private record Fixture(SemanticMemoryService service, InMemoryMemoryStore store,
                           FakeVectorIndex index, FakeChatPort chat,
                           ConsolidationService consolidation) {
    }

    private Fixture newFixture(int quota) {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        FakeVectorIndex index = new FakeVectorIndex();
        FakeEmbeddingPort embedding = new FakeEmbeddingPort();
        FakeChatPort chat = new FakeChatPort();
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1);
        SemanticMemoryService service = new SemanticMemoryService(
                new SemanticMemoryService.Dependencies(store, index, embedding,
                        RetrievalConfig.defaults(), quota, ids, null, List.of(),
                        Clock.systemUTC()));
        ConsolidationService consolidation = new ConsolidationService(
                new ConsolidationService.Dependencies(service,
                        new MemoryExtractor(chat, new PromptResolver()),
                        new MemoryDecider(chat, new PromptResolver()),
                        index, embedding, 0.92, 0, Clock.systemUTC()));
        return new Fixture(service, store, index, chat, consolidation);
    }

    @Test
    void rememberThenSemanticRecall() {
        Fixture f = newFixture(100);
        f.service().remember(MemoryItem.builder()
                .scope(USER_U1).kind(MemoryKind.PREFERENCE)
                .content("用户习惯把部署坐标调整到集结地域东侧")
                .provenance(Provenance.userExplicit("u1", "s1#1"))
                .build());
        List<SemanticMemoryService.ScoredMemory> hits = f.service().recall(
                "调整部署位置的习惯", MemoryQuery.builder()
                        .scopeType(ScopeType.USER).ownerId("u1").build());
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).entry().getAbstractText().length() <= 50);
    }

    @Test
    void recallDoesNotCrossScope() {
        Fixture f = newFixture(100);
        f.service().remember(MemoryItem.builder()
                .scope(MemoryScope.ofUser(null, "u2")).content("u2 的私有偏好").build());
        List<SemanticMemoryService.ScoredMemory> hits = f.service().recall(
                "私有偏好", MemoryQuery.builder().scopeType(ScopeType.USER).ownerId("u1").build());
        assertTrue(hits.isEmpty());
    }

    @Test
    void correctHonorsOptimisticLock() {
        Fixture f = newFixture(100);
        MemoryItem saved = f.service().remember(MemoryItem.builder()
                .scope(USER_U1).content("原始内容").build());
        f.service().correct(saved.getMemoryId(), "更正后内容", null,
                Provenance.userExplicit("u1", "s1#2"));
        assertEquals("更正后内容", f.service().get(saved.getMemoryId()).orElseThrow().getContent());
        // 存储层乐观锁：陈旧版本号更新被拒
        MemoryItem stale = saved.copy();
        stale.setContent("并发写");
        assertTrue(!f.store().update(stale, saved.getVersion()));
        // CREATE + UPDATE 两条审计
        assertEquals(2, f.service().history(saved.getMemoryId()).size());
    }

    @Test
    void correctRegeneratesAbstractWhenNotGiven() {
        Fixture f = newFixture(100);
        MemoryItem saved = f.service().remember(MemoryItem.builder()
                .scope(USER_U1).content("用户是湖南人").build());
        // 未显式给新索引 → 按新正文重派生，禁止"正文已改、索引停留旧值"
        f.service().correct(saved.getMemoryId(), "用户是上海人", null,
                Provenance.userExplicit("u1", "s1#2"));
        MemoryItem after = f.service().get(saved.getMemoryId()).orElseThrow();
        assertEquals("用户是上海人", after.getContent());
        assertEquals("用户是上海人", after.getAbstractText());
        // 显式给新索引 → 采用显式值
        f.service().correct(saved.getMemoryId(), null, "籍贯：上海",
                Provenance.userExplicit("u1", "s1#3"));
        assertEquals("籍贯：上海", f.service().get(saved.getMemoryId()).orElseThrow().getAbstractText());
    }

    @Test
    void recordAccessDoesNotTouchVersion() {
        Fixture f = newFixture(100);
        MemoryItem saved = f.service().remember(MemoryItem.builder()
                .scope(USER_U1).content("被反复召回的记忆").build());
        long versionBefore = saved.getVersion();
        f.service().recall("被反复召回", MemoryQuery.builder()
                .scopeType(ScopeType.USER).ownerId("u1").build());
        f.service().recall("被反复召回", MemoryQuery.builder()
                .scopeType(ScopeType.USER).ownerId("u1").build());
        MemoryItem after = f.service().get(saved.getMemoryId()).orElseThrow();
        // 命中回馈只推进访问计数与最近召回时刻，version 只随内容修订推进
        assertEquals(versionBefore, after.getVersion());
        assertEquals(2, after.getAccessCount());
        assertTrue(after.getLastAccessedAt() != null);
    }

    @Test
    void consolidationAppliesDeletePlusAddForRefutation() {
        Fixture f = newFixture(100);
        MemoryItem old = f.service().remember(MemoryItem.builder()
                .scope(USER_U1).kind(MemoryKind.PREFERENCE).content("用户不喜欢吃坚果").build());
        f.chat().reply("[{\"kind\":\"preference\",\"abstract\":\"喜欢坚果\",\"content\":\"用户喜欢吃坚果\","
                + "\"importance\":7,\"evidence\":\"喜欢坚果\"}]");
        // 决策器输出"推翻旧记忆 + 新增"两个操作 → 旧作废、新入库，矛盾不共存
        f.chat().reply("[{\"op\":\"DELETE\",\"targetId\":\"" + old.getMemoryId() + "\"},"
                + "{\"op\":\"ADD\"}]");
        int applied = f.consolidation().consolidate(USER_U1, "用户：喜欢坚果", "s1#r3");
        assertEquals(2, applied);
        assertEquals(MemoryStatus.INVALIDATED,
                f.service().get(old.getMemoryId()).orElseThrow().getStatus());
        List<MemoryItem> active = f.service().list(MemoryQuery.builder()
                .scopeType(ScopeType.USER).ownerId("u1").build());
        assertEquals(1, active.size());
        assertEquals("用户喜欢吃坚果", active.get(0).getContent());
    }

    @Test
    void invalidateRemovesFromRecall() {
        Fixture f = newFixture(100);
        MemoryItem saved = f.service().remember(MemoryItem.builder()
                .scope(USER_U1).content("将被作废的事实").build());
        f.service().invalidate(saved.getMemoryId(), "被证伪");
        List<SemanticMemoryService.ScoredMemory> hits = f.service().recall(
                "被证伪的事实", MemoryQuery.builder()
                        .scopeType(ScopeType.USER).ownerId("u1").build());
        assertTrue(hits.isEmpty());
        // 墓碑仍在（作废不删除）
        assertEquals(MemoryStatus.INVALIDATED,
                f.service().get(saved.getMemoryId()).orElseThrow().getStatus());
    }

    @Test
    void quotaEvictsLowestImportance() {
        Fixture f = newFixture(2);
        f.service().remember(MemoryItem.builder().scope(USER_U1).content("重要记忆").importance(9).build());
        f.service().remember(MemoryItem.builder().scope(USER_U1).content("次要记忆").importance(2).build());
        f.service().remember(MemoryItem.builder().scope(USER_U1).content("新记忆").importance(5).build());
        List<MemoryItem> active = f.service().list(MemoryQuery.builder()
                .scopeType(ScopeType.USER).ownerId("u1").build());
        assertEquals(2, active.size());
        assertTrue(active.stream().noneMatch(i -> i.getContent().equals("次要记忆")));
    }

    @Test
    void consolidationAddsNewFactViaPipeline() {
        Fixture f = newFixture(100);
        f.chat().reply("{\"facts\":[{\"kind\":\"preference\",\"abstract\":\"偏好A\",\"content\":\"用户偏好方案A\","
                + "\"importance\":7,\"evidence\":\"就用方案A\"}]}");
        f.chat().reply("{\"operations\":[{\"op\":\"ADD\"}]}");
        int applied = f.consolidation().consolidate(USER_U1, "用户：就用方案A", "s1#r1");
        assertEquals(1, applied);
        assertEquals(1, f.service().list(MemoryQuery.builder()
                .scopeType(ScopeType.USER).ownerId("u1").build()).size());
    }

    @Test
    void consolidationSkipsDuplicateWithoutLlmDecision() {
        Fixture f = newFixture(100);
        f.service().remember(MemoryItem.builder().scope(USER_U1).content("用户偏好方案A").build());
        f.chat().reply("[{\"kind\":\"preference\",\"abstract\":\"偏好A\",\"content\":\"用户偏好方案A\","
                + "\"importance\":7,\"evidence\":\"就用方案A\"}]");
        // 去重阈值内且文本归一化相等 → 直接 NOOP，不消耗决策调用（无第二条回复脚本）
        int applied = f.consolidation().consolidate(USER_U1, "用户：就用方案A", "s1#r2");
        assertEquals(0, applied);
        assertEquals(1, f.service().list(MemoryQuery.builder()
                .scopeType(ScopeType.USER).ownerId("u1").build()).size());
    }

    @Test
    void eraseRemovesBodyAndVector() {
        Fixture f = newFixture(100);
        MemoryItem saved = f.service().remember(MemoryItem.builder()
                .scope(USER_U1).content("隐私内容").build());
        f.service().erase(saved.getMemoryId(), "admin", "被遗忘权");
        assertTrue(f.service().get(saved.getMemoryId()).isEmpty());
        assertTrue(f.service().recall("隐私内容", MemoryQuery.builder()
                .scopeType(ScopeType.USER).ownerId("u1").build()).isEmpty());
        // 审计链只剩脱敏 ERASE 锚点
        assertEquals(1, f.service().history(saved.getMemoryId()).size());
    }
}
