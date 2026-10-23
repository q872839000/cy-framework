// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词解析器测试（三级覆盖链）。
 *
 * @author pcy
 */
class PromptResolverTest {

    @Test
    void resolvesBundledDefault() {
        PromptResolver resolver = new PromptResolver();
        String prompt = resolver.resolve(PromptKey.CONTEXT_SUMMARY);
        assertTrue(prompt.contains("conversation summarizer"));
    }

    @Test
    void programmaticOverrideWinsOverConfig() {
        PromptResolver resolver = new PromptResolver();
        resolver.configureOverride(PromptKey.CONTEXT_SUMMARY, "classpath:prompts/fact-extract.md");
        resolver.override(PromptKey.CONTEXT_SUMMARY, "业务自定义摘要提示词");
        assertEquals("业务自定义摘要提示词", resolver.resolve(PromptKey.CONTEXT_SUMMARY));
    }

    @Test
    void configOverrideLoadsResource() {
        PromptResolver resolver = new PromptResolver();
        resolver.configureOverride(PromptKey.CONTEXT_SUMMARY, "classpath:prompts/fact-extract.md");
        assertTrue(resolver.resolve(PromptKey.CONTEXT_SUMMARY).contains("memory extractor"));
    }

    @Test
    void allBundledDefaultsExist() {
        PromptResolver resolver = new PromptResolver();
        for (PromptKey key : PromptKey.values()) {
            assertTrue(resolver.resolve(key).length() > 50, key + " 默认稿缺失或过短");
        }
    }
}
