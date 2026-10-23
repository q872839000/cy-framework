// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.semantic;

import com.cy.orrery.memory.api.MemoryKind;
import com.cy.orrery.memory.prompt.PromptResolver;
import com.cy.orrery.memory.support.FakeChatPort;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提取/决策的 LLM 输出解析健壮性测试。
 *
 * @author pcy
 */
class ExtractorDeciderParseTest {

    private final PromptResolver prompts = new PromptResolver();

    @Test
    void extractorParsesFencedJson() {
        MemoryExtractor extractor = new MemoryExtractor(new FakeChatPort(), prompts);
        List<MemoryExtractor.Candidate> candidates = extractor.parse("""
                ```json
                {"facts":[{"kind":"preference","abstract":"偏好","content":"用户偏好深色主题","importance":8,"evidence":"用深色"}]}
                ```
                """);
        assertEquals(1, candidates.size());
        assertEquals(MemoryKind.PREFERENCE, candidates.get(0).kind());
        assertEquals(8, candidates.get(0).importance());
    }

    @Test
    void extractorToleratesBareArray() {
        // 降级到纯提示词档时模型可能直接输出裸数组
        MemoryExtractor extractor = new MemoryExtractor(new FakeChatPort(), prompts);
        List<MemoryExtractor.Candidate> candidates = extractor.parse(
                "[{\"kind\":\"fact\",\"abstract\":\"事实\",\"content\":\"用户住在上海\",\"importance\":6}]");
        assertEquals(1, candidates.size());
        assertEquals(MemoryKind.FACT, candidates.get(0).kind());
    }

    @Test
    void extractorReturnsEmptyOnGarbage() {
        MemoryExtractor extractor = new MemoryExtractor(new FakeChatPort(), prompts);
        assertTrue(extractor.parse("这不是 JSON").isEmpty());
        assertTrue(extractor.parse("").isEmpty());
        assertTrue(extractor.parse(null).isEmpty());
        assertTrue(extractor.parse("{\"not\":\"array\"}").isEmpty());
        assertTrue(extractor.parse("{\"facts\":\"not-array\"}").isEmpty());
    }

    @Test
    void extractorClampsImportanceAndSkipsBlankContent() {
        MemoryExtractor extractor = new MemoryExtractor(new FakeChatPort(), prompts);
        List<MemoryExtractor.Candidate> candidates = extractor.parse(
                "{\"facts\":[{\"kind\":\"x\",\"content\":\"\",\"importance\":5},"
                        + "{\"kind\":\"fact\",\"content\":\"有效事实\",\"importance\":99}]}");
        assertEquals(1, candidates.size());
        assertEquals(10, candidates.get(0).importance());
        assertEquals(MemoryKind.FACT, candidates.get(0).kind());
    }

    @Test
    void deciderDefaultsToNoopOnGarbage() {
        MemoryDecider decider = new MemoryDecider(new FakeChatPort(), prompts);
        assertTrue(decider.parse("垃圾输出").isEmpty());
        assertTrue(decider.parse("").isEmpty());
        assertTrue(decider.parse(null).isEmpty());
    }

    @Test
    void deciderParsesUpdateOutcome() {
        MemoryDecider decider = new MemoryDecider(new FakeChatPort(), prompts);
        List<MemoryDecider.DecisionOutcome> outcomes = decider.parse(
                "{\"operations\":[{\"op\":\"UPDATE\",\"targetId\":\"123\",\"content\":\"合并后内容\"}]}");
        assertEquals(1, outcomes.size());
        assertEquals(MemoryDecider.Decision.UPDATE, outcomes.get(0).decision());
        assertEquals("123", outcomes.get(0).targetId());
        assertEquals("合并后内容", outcomes.get(0).mergedContent());
    }

    @Test
    void deciderParsesMultipleOperationsInOrder() {
        MemoryDecider decider = new MemoryDecider(new FakeChatPort(), prompts);
        // 矛盾裁决：DELETE 被推翻的旧记忆 + ADD 新记忆，两个操作都必须保留
        List<MemoryDecider.DecisionOutcome> outcomes = decider.parse(
                "{\"operations\":[{\"op\":\"DELETE\",\"targetId\":\"111\"},"
                        + "{\"op\":\"NOOP\"},"
                        + "{\"op\":\"ADD\"},"
                        + "{\"op\":\"坏操作\",\"targetId\":\"999\"}]}");
        assertEquals(2, outcomes.size());
        assertEquals(MemoryDecider.Decision.DELETE, outcomes.get(0).decision());
        assertEquals("111", outcomes.get(0).targetId());
        assertEquals(MemoryDecider.Decision.ADD, outcomes.get(1).decision());
    }

    @Test
    void deciderToleratesBareArrayAndSingleObject() {
        // 降级到纯提示词档时模型可能直接输出裸数组或单个操作对象
        MemoryDecider decider = new MemoryDecider(new FakeChatPort(), prompts);
        List<MemoryDecider.DecisionOutcome> fromArray = decider.parse("[{\"op\":\"ADD\"}]");
        assertEquals(1, fromArray.size());
        assertEquals(MemoryDecider.Decision.ADD, fromArray.get(0).decision());
        List<MemoryDecider.DecisionOutcome> fromObject = decider.parse(
                "{\"op\":\"DELETE\",\"targetId\":\"7\"}");
        assertEquals(1, fromObject.size());
        assertEquals(MemoryDecider.Decision.DELETE, fromObject.get(0).decision());
        assertEquals("7", fromObject.get(0).targetId());
    }

    @Test
    void deciderUnavailableChatYieldsNoop() {
        MemoryDecider decider = new MemoryDecider(new FakeChatPort().available(false), prompts);
        List<MemoryDecider.DecisionOutcome> outcomes = decider.decide(
                new MemoryExtractor.Candidate(MemoryKind.FACT, "a", "内容", 5, null), List.of());
        assertTrue(outcomes.isEmpty());
    }
}
