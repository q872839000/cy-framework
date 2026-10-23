package com.cy.ai.config;

import com.cy.ai.provider.AiProvider;
import com.cy.core.exception.CYException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.resources.ConnectionProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AiProviderRegistry} 单元测试
 * <p>
 * 以记录式假工厂驱动注册表：验证初始装配、无变更复用、增/删/改 diff、
 * 连接池释放、非法配置拒绝与泄漏防护、角色路由与能力兜底。
 * </p>
 *
 * @author pcy
 * @since 2026/10/02
 */
class AiProviderRegistryTest {

    /**
     * 记录式假工厂：按 provider 名记录每次创建的句柄；连接池用 mock 以便验证释放行为。
     * provider 的 embedding/rerank 能力按配置启用状态打桩。
     */
    private static class FakeFactory extends AiProviderFactory {

        private final Map<String, List<ProviderHandle>> createdByName = new HashMap<>();

        FakeFactory() {
            super(new ObjectMapper());
        }

        @Override
        public ProviderHandle create(String name, AiProperties.ProviderConfig config) {
            AiProvider provider = mock(AiProvider.class);
            lenient().when(provider.name()).thenReturn(name);
            lenient().when(provider.supportsEmbeddings()).thenReturn(
                    config.getEmbedding() != null && config.getEmbedding().isEnabled());
            lenient().when(provider.supportsRerank()).thenReturn(
                    config.getRerank() != null && config.getRerank().isEnabled());
            ConnectionProvider pool = mock(ConnectionProvider.class);
            lenient().when(pool.disposeLater()).thenReturn(Mono.empty());
            ProviderHandle handle = new ProviderHandle(provider, pool);
            createdByName.computeIfAbsent(name, k -> new ArrayList<>()).add(handle);
            return handle;
        }

        /** 指定 provider 最近一次创建的句柄（从未创建返回 null） */
        ProviderHandle lastCreated(String name) {
            List<ProviderHandle> list = createdByName.get(name);
            return list == null || list.isEmpty() ? null : list.get(list.size() - 1);
        }

        /** 指定 provider 累计创建次数 */
        int createdCount(String name) {
            return createdByName.getOrDefault(name, List.of()).size();
        }
    }

    private final FakeFactory factory = new FakeFactory();

    // ========================= 初始装配 =========================

    @Test
    void initialBuildExposesProvidersAndDefault() {
        AiProviderRegistry registry = new AiProviderRegistry(factory, props("alpha", "alpha", "beta"));

        assertThat(registry.snapshot().providers().keySet()).containsExactly("alpha", "beta");
        assertThat(registry.snapshot().defaultProvider()).isEqualTo("alpha");
        assertThat(registry.requireProvider("alpha"))
                .isSameAs(factory.lastCreated("alpha").provider());
        assertThat(registry.requireDefaultProvider())
                .isSameAs(factory.lastCreated("alpha").provider());
    }

    @Test
    void initialBuildFailsFastWhenDefaultMissing() {
        assertThatThrownBy(() -> new AiProviderRegistry(factory, props("ghost", "alpha")))
                .isInstanceOf(CYException.class)
                .satisfies(e -> assertThat(((CYException) e).getErrorCode()).isEqualTo(40902));
    }

    @Test
    void initialBuildRejectsBlankBaseUrl() {
        AiProperties properties = props("alpha", "alpha");
        properties.getProviders().get("alpha").setBaseUrl(" ");

        assertThatThrownBy(() -> new AiProviderRegistry(factory, properties))
                .isInstanceOf(CYException.class)
                .satisfies(e -> assertThat(((CYException) e).getErrorCode()).isEqualTo(40906));
    }

    // ========================= 热更新 diff =========================

    @Test
    void refreshWithoutChangesReusesAllInstances() {
        AiProviderRegistry registry = new AiProviderRegistry(factory, props("alpha", "alpha", "beta"));
        AiProvider alphaBefore = registry.requireProvider("alpha");
        AiProvider betaBefore = registry.requireProvider("beta");

        boolean accepted = registry.refresh(props("alpha", "alpha", "beta"));

        assertThat(accepted).isTrue();
        assertThat(registry.requireProvider("alpha")).isSameAs(alphaBefore);
        assertThat(registry.requireProvider("beta")).isSameAs(betaBefore);
        assertThat(factory.createdCount("alpha")).isEqualTo(1);
        assertThat(factory.createdCount("beta")).isEqualTo(1);
        verify(factory.lastCreated("alpha").connectionProvider(), never()).disposeLater();
        verify(factory.lastCreated("beta").connectionProvider(), never()).disposeLater();
    }

    @Test
    void refreshAddsNewProvider() {
        AiProviderRegistry registry = new AiProviderRegistry(factory, props("alpha", "alpha"));
        AiProvider alphaBefore = registry.requireProvider("alpha");

        boolean accepted = registry.refresh(props("alpha", "alpha", "gamma"));

        assertThat(accepted).isTrue();
        assertThat(registry.snapshot().providers().keySet()).containsExactly("alpha", "gamma");
        assertThat(registry.requireProvider("alpha")).isSameAs(alphaBefore);
        assertThat(factory.createdCount("gamma")).isEqualTo(1);
    }

    @Test
    void refreshRemovesProviderAndDisposesPool() {
        AiProviderRegistry registry = new AiProviderRegistry(factory, props("alpha", "alpha", "beta"));
        ConnectionProvider betaPool = factory.lastCreated("beta").connectionProvider();

        boolean accepted = registry.refresh(props("alpha", "alpha"));

        assertThat(accepted).isTrue();
        assertThat(registry.snapshot().providers().keySet()).containsExactly("alpha");
        assertThat(factory.createdCount("beta")).isEqualTo(1);
        verify(betaPool).disposeLater();
    }

    @Test
    void refreshRebuildsOnlyChangedProvider() {
        AiProviderRegistry registry = new AiProviderRegistry(factory, props("alpha", "alpha", "beta"));
        AiProvider alphaBefore = registry.requireProvider("alpha");
        AiProvider betaBefore = registry.requireProvider("beta");
        ConnectionProvider betaPool = factory.lastCreated("beta").connectionProvider();

        AiProperties updated = props("alpha", "alpha", "beta");
        updated.getProviders().get("beta").getChat().getOptions().setTemperature(0.1);
        boolean accepted = registry.refresh(updated);

        assertThat(accepted).isTrue();
        assertThat(registry.requireProvider("alpha")).isSameAs(alphaBefore);
        assertThat(registry.requireProvider("beta")).isNotSameAs(betaBefore);
        assertThat(factory.createdCount("beta")).isEqualTo(2);
        verify(betaPool).disposeLater();
    }

    @Test
    void refreshRejectsInvalidDefaultAndKeepsOldSnapshot() {
        AiProviderRegistry registry = new AiProviderRegistry(factory, props("alpha", "alpha", "beta"));
        AiProvider alphaBefore = registry.requireProvider("alpha");

        boolean accepted = registry.refresh(props("ghost", "alpha", "beta"));

        assertThat(accepted).isFalse();
        assertThat(registry.requireProvider("alpha")).isSameAs(alphaBefore);
        assertThat(registry.snapshot().defaultProvider()).isEqualTo("alpha");
        // 配置未变，不应产生任何新建/释放
        assertThat(factory.createdCount("alpha")).isEqualTo(1);
        assertThat(factory.createdCount("beta")).isEqualTo(1);
    }

    @Test
    void refreshRejectionDisposesHandlesCreatedInFailedRound() {
        AiProviderRegistry registry = new AiProviderRegistry(factory, props("alpha", "alpha", "beta"));
        ConnectionProvider oldBetaPool = factory.lastCreated("beta").connectionProvider();

        // beta 配置变更（触发重建）+ 默认指向不存在（触发整体拒绝）
        AiProperties bad = props("ghost", "alpha", "beta");
        bad.getProviders().get("beta").getChat().getOptions().setTemperature(0.1);
        boolean accepted = registry.refresh(bad);

        assertThat(accepted).isFalse();
        // 失败轮新建的 beta' 句柄必须被释放（防连接池泄漏）
        assertThat(factory.createdCount("beta")).isEqualTo(2);
        verify(factory.lastCreated("beta").connectionProvider()).disposeLater();
        // 旧 beta 仍在服役，其连接池不得动
        verify(oldBetaPool, never()).disposeLater();
        assertThat(registry.requireProvider("beta"))
                .isSameAs(factory.createdByName.get("beta").get(0).provider());
    }

    // ========================= 查询与角色路由 =========================

    @Test
    void requireProviderThrows40901WhenMissing() {
        AiProviderRegistry registry = new AiProviderRegistry(factory, props("alpha", "alpha"));

        assertThatThrownBy(() -> registry.requireProvider("ghost"))
                .isInstanceOf(CYException.class)
                .satisfies(e -> assertThat(((CYException) e).getErrorCode()).isEqualTo(40901));
    }

    @Test
    void embeddingRoleRoutingAndFallback() {
        AiProperties properties = props("alpha", "alpha", "beta");
        enableEmbedding(properties, "alpha");
        // 角色路由指向无 embedding 能力的 beta → 回退到第一个具备能力的 alpha
        properties.getRoles().put("embedding", "beta");
        AiProviderRegistry registry = new AiProviderRegistry(factory, properties);

        assertThat(registry.requireEmbeddingProvider())
                .isSameAs(registry.requireProvider("alpha"));
        assertThat(registry.hasEmbeddingProvider()).isTrue();

        // 路由修正为 alpha → 直接使用；配置未变，alpha 实例复用
        AiProperties refreshed = props("alpha", "alpha", "beta");
        enableEmbedding(refreshed, "alpha");
        refreshed.getRoles().put("embedding", "alpha");
        registry.refresh(refreshed);
        assertThat(registry.requireEmbeddingProvider())
                .isSameAs(registry.requireProvider("alpha"));
        assertThat(factory.createdCount("alpha")).isEqualTo(1);
    }

    @Test
    void embeddingUnavailableThrows40904() {
        AiProviderRegistry registry = new AiProviderRegistry(factory, props("alpha", "alpha"));

        assertThat(registry.hasEmbeddingProvider()).isFalse();
        assertThatThrownBy(registry::requireEmbeddingProvider)
                .isInstanceOf(CYException.class)
                .satisfies(e -> assertThat(((CYException) e).getErrorCode()).isEqualTo(40904));
    }

    @Test
    void rerankRoleRoutingAndUnavailable40905() {
        AiProperties properties = props("alpha", "alpha", "beta");
        enableRerank(properties, "beta");
        properties.getRoles().put("rerank", "beta");
        AiProviderRegistry registry = new AiProviderRegistry(factory, properties);

        assertThat(registry.requireRerankProvider())
                .isSameAs(registry.requireProvider("beta"));
        assertThat(registry.hasRerankProvider()).isTrue();

        AiProviderRegistry noRerank = new AiProviderRegistry(factory, props("alpha", "alpha"));
        assertThat(noRerank.hasRerankProvider()).isFalse();
        assertThatThrownBy(noRerank::requireRerankProvider)
                .isInstanceOf(CYException.class)
                .satisfies(e -> assertThat(((CYException) e).getErrorCode()).isEqualTo(40905));
    }

    // ========================= 测试数据构造 =========================

    /** 构造配置：指定默认 provider + 若干 chat provider（base-url 按名区分）。 */
    private AiProperties props(String defaultProvider, String... providerNames) {
        AiProperties properties = new AiProperties();
        properties.setDefaultProvider(defaultProvider);
        Map<String, AiProperties.ProviderConfig> providers = new LinkedHashMap<>();
        for (String name : providerNames) {
            providers.put(name, chatProvider("http://" + name + ".local", "m-" + name));
        }
        properties.setProviders(providers);
        return properties;
    }

    private AiProperties.ProviderConfig chatProvider(String baseUrl, String model) {
        AiProperties.ProviderConfig config = new AiProperties.ProviderConfig();
        config.setBaseUrl(baseUrl);
        config.setApiKey("test-key");
        config.getChat().getOptions().setModel(model);
        return config;
    }

    private void enableEmbedding(AiProperties properties, String name) {
        AiProperties.ProviderConfig config = properties.getProviders().get(name);
        config.getEmbedding().setEnabled(true);
        config.getEmbedding().getOptions().setModel("bge-m3");
    }

    private void enableRerank(AiProperties properties, String name) {
        AiProperties.ProviderConfig config = properties.getProviders().get(name);
        config.getRerank().setEnabled(true);
        config.getRerank().getOptions().setModel("bge-reranker-v2-m3");
    }
}
