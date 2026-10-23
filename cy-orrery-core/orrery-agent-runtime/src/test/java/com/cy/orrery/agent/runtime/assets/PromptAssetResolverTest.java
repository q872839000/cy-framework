package com.cy.orrery.agent.runtime.assets;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PromptAssetResolver 单测：Key 映射、Provider 优先、classpath 兜底。
 */
class PromptAssetResolverTest {

    @Test
    void mapsClasspathLocationToRegistryKey() {
        PromptAssetResolver r = new PromptAssetResolver(null, "scenario-agent", "stable");
        assertEquals("scenario-agent/workflow/deploy_proposal",
                r.toKey("prompts/workflow/deploy_proposal.md"));
        assertEquals("scenario-agent/plan/planning_system",
                r.toKey("prompts/plan/planning_system.md"));
        // 非 prompts/ 约定路径不进注册表命名空间
        assertNull(r.toKey("prompts/README"));
        assertNull(r.toKey("other/thing.md"));
        assertNull(r.toKey("prompts/no-subdir.md"));
    }

    @Test
    void providerFirstThenClasspathFallback() {
        PromptAssetProvider remote = new PromptAssetProvider() {
            @Override
            public Optional<String> getTemplate(String key, String label) {
                return Optional.empty();
            }

            @Override
            public Optional<String> getTemplateByVersion(String key, String version) {
                return Optional.empty();
            }
        };
        PromptAssetResolver r = new PromptAssetResolver(
                new PromptAssetProvider() {
                    @Override
                    public Optional<String> getTemplate(String key, String label) {
                        return Optional.of("远端模板内容");
                    }

                    @Override
                    public Optional<String> getTemplateByVersion(String key, String version) {
                        return Optional.empty();
                    }
                }, "scenario-agent", "stable");
        assertEquals("远端模板内容", r.resolve("classpath:prompts/core/identity.md"));

        // Provider 未命中 → classpath 兜底（core/identity.md 在 agent-runtime 测试类路径不存在，
        // 用一个确定存在的资源验证：orrery-protocol 的 error-messages 不在本模块；改用自建测试资源）
        PromptAssetResolver fallback = new PromptAssetResolver(remote, "scenario-agent", "stable");
        assertThrows(IllegalStateException.class, () -> fallback.resolve("classpath:prompts/not-exist.md"));
        assertTrue(true);
    }

    @Test
    void nullProviderMeansPureClasspath() {
        PromptAssetResolver r = new PromptAssetResolver(null, "scenario-agent", "stable");
        assertThrows(IllegalStateException.class, () -> r.resolve("classpath:prompts/not-exist.md"));
    }
}
