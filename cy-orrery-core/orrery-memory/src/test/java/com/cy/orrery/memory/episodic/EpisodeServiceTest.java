// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.episodic;

import com.cy.orrery.memory.api.MemoryKind;
import com.cy.orrery.memory.api.MemoryQuery;
import com.cy.orrery.memory.api.MemoryScope;
import com.cy.orrery.memory.api.ScopeType;
import com.cy.orrery.memory.prompt.PromptResolver;
import com.cy.orrery.memory.support.FakeChatPort;
import com.cy.orrery.memory.support.FakeEmbeddingPort;
import com.cy.orrery.memory.support.FakeVectorIndex;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 情景记忆服务测试（沉淀 + 召回 + LLM 不可用降级）。
 *
 * @author pcy
 */
class EpisodeServiceTest {

    /** 测试用内存卡片存储。 */
    private static final class InMemoryEpisodeStore implements EpisodeStore {
        private final java.util.Map<String, EpisodeRecord> data = new java.util.HashMap<>();

        @Override
        public void save(EpisodeRecord record) {
            data.put(record.getEpisodeId(), record);
        }

        @Override
        public Optional<EpisodeRecord> get(String episodeId) {
            return Optional.ofNullable(data.get(episodeId));
        }

        @Override
        public void delete(String episodeId) {
            data.remove(episodeId);
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private static final MemoryScope USER_U1 = MemoryScope.ofUser(null, "u1");

    private EpisodeService newService(InMemoryEpisodeStore store, FakeVectorIndex index,
                                      FakeChatPort chat) {
        return new EpisodeService(store, index, new FakeEmbeddingPort(), chat,
                new PromptResolver(), Clock.systemUTC());
    }

    @Test
    void settlePersistsCardAndIndexesVector() {
        InMemoryEpisodeStore store = new InMemoryEpisodeStore();
        FakeVectorIndex index = new FakeVectorIndex();
        FakeChatPort chat = new FakeChatPort().reply(
                "{\"title\":\"想定部署调整\",\"summary\":\"用户把3号车从西侧调整到东侧集结地\","
                        + "\"outcome\":\"success\",\"entities\":[\"3号车\"],\"lessons\":[]}");
        EpisodeService service = newService(store, index, chat);
        EpisodeRecord record = service.settle("s1", USER_U1, "用户：把3号车移动到东侧");
        assertEquals("s1", record.getEpisodeId());
        assertEquals("想定部署调整", record.getTitle());
        assertEquals("u1", record.getUserId());
        // 向量已入索引（kind=EPISODE）
        assertEquals(1, index.search(new FakeEmbeddingPort().embed("部署调整"),
                MemoryQuery.builder().scopeType(ScopeType.USER).ownerId("u1")
                        .kinds(java.util.Set.of(MemoryKind.EPISODE)).build(), 5).size());
    }

    @Test
    void recallReturnsStoredCards() {
        InMemoryEpisodeStore store = new InMemoryEpisodeStore();
        FakeVectorIndex index = new FakeVectorIndex();
        FakeChatPort chat = new FakeChatPort().reply(
                "{\"title\":\"机动规划\",\"summary\":\"为车队规划了到集结地域的机动路线\","
                        + "\"outcome\":\"success\",\"entities\":[],\"lessons\":[]}");
        EpisodeService service = newService(store, index, chat);
        service.settle("s2", USER_U1, "用户：规划机动路线");
        List<EpisodeRecord> hits = service.recall("上次机动规划怎么做的", USER_U1, 5);
        assertEquals(1, hits.size());
        assertEquals("机动规划", hits.get(0).getTitle());
    }

    @Test
    void llmUnavailableSkipsSettle() {
        FakeChatPort chat = new FakeChatPort().available(false);
        EpisodeService service = newService(new InMemoryEpisodeStore(), new FakeVectorIndex(), chat);
        assertNull(service.settle("s3", USER_U1, "用户：部署"));
    }

    @Test
    void garbageOutputSkipsSettle() {
        FakeChatPort chat = new FakeChatPort().reply("不是 JSON");
        EpisodeService service = newService(new InMemoryEpisodeStore(), new FakeVectorIndex(), chat);
        assertNull(service.settle("s4", USER_U1, "用户：部署"));
    }
}
