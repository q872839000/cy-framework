package com.cy.ai.config;

import com.cy.ai.AiRole;
import com.cy.ai.provider.AiProvider;
import com.cy.core.exception.CYException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * AI Provider 动态注册表
 * <p>
 * 持有当前生效的 provider 快照（{@link Snapshot}，不可变），对 {@link com.cy.ai.AiFacade}
 * 提供按名/默认/角色路由的查询。配置来源分两层：本地 {@code cy.ai.*}（兜底，独立运行模式）
 * 与配置中心远程层（Spring 属性源按优先级天然合并，远程同 key 覆盖本地）。
 * </p>
 * <p>
 * <b>热更新</b>：{@link #refresh(AiProperties)} 以全量新配置与当前快照逐 provider 比对——
 * 配置未变的复用原实例（连接池不动），新增/变更的经 {@link AiProviderFactory} 重建，
 * 消失的释放连接池；整体校验失败时拒绝并保留旧快照，不影响在途调用。
 * 无配置中心环境下 refresh 从不触发，行为与启动期一次性装配完全一致。
 * </p>
 *
 * @author pcy
 * @since 2026/10/02
 */
public class AiProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiProviderRegistry.class);

    /** Provider 装配工厂（构建/重建实例） */
    private final AiProviderFactory factory;

    /** 当前生效快照（原子替换，读写无需加锁） */
    private final AtomicReference<Snapshot> snapshotRef;

    /**
     * 构建注册表并加载初始快照
     * <p>
     * 初始配置非法（如默认 provider 未定义）时抛 {@link CYException} 启动失败——
     * 与历史行为一致（fail fast）。
     * </p>
     */
    public AiProviderRegistry(AiProviderFactory factory, AiProperties properties) {
        this.factory = factory;
        Snapshot initial = buildSnapshot(properties, null);
        this.snapshotRef = new AtomicReference<>(initial);
        log.info("AI Provider 注册表初始化完成，默认 provider: {}, 可用: {}",
                initial.defaultProvider(), initial.providers().keySet());
    }

    /**
     * 当前生效快照（不可变，可安全跨线程读取）
     */
    public Snapshot snapshot() {
        return snapshotRef.get();
    }

    /**
     * 全量刷新配置（热更新入口）
     *
     * @param properties 合并后的最新 cy.ai 配置（远程层与本地层已由 Spring 属性源合并）
     * @return true 已采纳（含无变更）；false 校验失败被拒绝、保留旧快照
     */
    public synchronized boolean refresh(AiProperties properties) {
        Snapshot prev = snapshotRef.get();
        final Snapshot next;
        try {
            next = buildSnapshot(properties, prev);
        } catch (RuntimeException e) {
            log.error("AI Provider 配置刷新被拒绝（保留旧配置）: {}", e.getMessage());
            return false;
        }
        snapshotRef.set(next);
        // 释放不再被新快照引用的旧句柄（被移除或配置变更重建的 provider 连接池）
        for (Map.Entry<String, Entry> old : prev.entries.entrySet()) {
            if (next.entries.get(old.getKey()) != old.getValue()) {
                old.getValue().handle.closeQuietly();
            }
        }
        logChanges(prev, next);
        return true;
    }

    // ========================= Provider 查询（供 AiFacade 委托） =========================

    /**
     * 按名获取 provider，不存在抛 40901
     */
    public AiProvider requireProvider(String name) {
        Snapshot current = snapshot();
        AiProvider provider = current.providers().get(name);
        if (provider == null) {
            throw new CYException(40901, name, current.providers().keySet());
        }
        return provider;
    }

    /**
     * 获取默认 provider，配置缺失抛 40902
     */
    public AiProvider requireDefaultProvider() {
        Snapshot current = snapshot();
        AiProvider provider = current.providers().get(current.defaultProvider());
        if (provider == null) {
            throw new CYException(40902, current.defaultProvider(), current.providers().keySet());
        }
        return provider;
    }

    /**
     * 解析 embedding provider：角色路由（cy.ai.roles.embedding）&gt; 第一个具备能力的 provider
     */
    public AiProvider requireEmbeddingProvider() {
        return requireCapabilityProvider(AiRole.EMBEDDING, AiProvider::supportsEmbeddings, 40904);
    }

    /**
     * 解析 rerank provider：角色路由（cy.ai.roles.rerank）&gt; 第一个具备能力的 provider
     */
    public AiProvider requireRerankProvider() {
        return requireCapabilityProvider(AiRole.RERANK, AiProvider::supportsRerank, 40905);
    }

    /**
     * 是否有可用的 embedding provider（调用方据此决定降级路径）
     */
    public boolean hasEmbeddingProvider() {
        return hasCapabilityProvider(AiRole.EMBEDDING, AiProvider::supportsEmbeddings);
    }

    /**
     * 是否有可用的 rerank provider（调用方据此决定降级路径）
     */
    public boolean hasRerankProvider() {
        return hasCapabilityProvider(AiRole.RERANK, AiProvider::supportsRerank);
    }

    /**
     * 能力路由统一入口：角色路由优先，未命中/不可用时自动探测第一个具备能力的 provider
     */
    private AiProvider requireCapabilityProvider(AiRole role, Predicate<AiProvider> capability, int errorCode) {
        Snapshot current = snapshot();
        String routed = current.roles().get(role.getId());
        if (routed != null) {
            AiProvider provider = current.providers().get(routed);
            if (provider != null && capability.test(provider)) {
                return provider;
            }
            log.warn("角色路由 cy.ai.roles.{}={} 不可用（不存在或无对应能力），自动探测替代", role.getId(), routed);
        }
        return current.providers().values().stream()
                .filter(capability)
                .findFirst()
                .orElseThrow(() -> new CYException(errorCode));
    }

    /**
     * 能力可用性探测（与 {@link #requireCapabilityProvider} 同一路由语义：路由不可用时同样回退探测）
     */
    private boolean hasCapabilityProvider(AiRole role, Predicate<AiProvider> capability) {
        Snapshot current = snapshot();
        String routed = current.roles().get(role.getId());
        if (routed != null) {
            AiProvider provider = current.providers().get(routed);
            if (provider != null && capability.test(provider)) {
                return true;
            }
        }
        return current.providers().values().stream().anyMatch(capability);
    }

    // ========================= 快照构建 =========================

    /**
     * 以全量配置构建新快照
     * <p>
     * 与上一快照（prev，可为 null）逐项比对复用未变更实例；任一环节失败时，
     * 本轮新建的句柄全部释放（防连接池泄漏）后向上抛出。
     * </p>
     */
    private Snapshot buildSnapshot(AiProperties properties, Snapshot prev) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<AiProviderFactory.ProviderHandle> createdThisRound = new ArrayList<>();
        try {
            for (Map.Entry<String, AiProperties.ProviderConfig> e : properties.getProviders().entrySet()) {
                String name = e.getKey();
                AiProperties.ProviderConfig config = e.getValue();

                if (config.getChat() != null && !config.getChat().isEnabled()) {
                    log.info("跳过禁用的 provider: {}", name);
                    continue;
                }

                Entry prevEntry = prev == null ? null : prev.entries.get(name);
                if (prevEntry != null && prevEntry.config.equals(config)) {
                    // 配置未变：复用原实例（连接池不动）
                    entries.put(name, prevEntry);
                    continue;
                }

                validate(name, config);
                AiProviderFactory.ProviderHandle handle = factory.create(name, config);
                createdThisRound.add(handle);
                entries.put(name, new Entry(handle, config));
                logRegistration(name, config);
            }

            String defaultProvider = properties.getDefaultProvider();
            if (defaultProvider == null || !entries.containsKey(defaultProvider)) {
                throw new CYException(40902, defaultProvider, entries.keySet());
            }
            Map<String, String> roles = properties.getRoles() == null
                    ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties.getRoles()));
            return new Snapshot(entries, defaultProvider, roles);
        } catch (RuntimeException ex) {
            createdThisRound.forEach(AiProviderFactory.ProviderHandle::closeQuietly);
            throw ex;
        }
    }

    /** provider 级配置校验（失败抛 40906，刷新场景整体拒绝）。 */
    private void validate(String name, AiProperties.ProviderConfig config) {
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new CYException(40906, name, "base-url 为空");
        }
    }

    /** 单 provider 注册日志（沿用原启动日志格式，热更新场景同样可见）。 */
    private void logRegistration(String name, AiProperties.ProviderConfig config) {
        AiProperties.ChatOptions options = config.getChat() != null ? config.getChat().getOptions() : null;
        String embeddingModel = config.getEmbedding() != null && config.getEmbedding().isEnabled()
                && config.getEmbedding().getOptions() != null
                ? config.getEmbedding().getOptions().getModel() : null;
        String rerankModel = config.getRerank() != null && config.getRerank().isEnabled()
                && config.getRerank().getOptions() != null
                ? config.getRerank().getOptions().getModel() : null;
        log.info("注册 AI Provider: name={}, baseUrl={}, model={}, embeddingModel={}, rerankModel={}, thinkingEnabled={}, thinkingControlMode={}",
                name, config.getBaseUrl(),
                options != null ? options.getModel() : null, embeddingModel, rerankModel,
                options != null && options.isThinkingEnabled(),
                options != null ? options.getThinkingControlMode() : null);
    }

    /** 刷新变更摘要日志（无变更时仅 debug）。 */
    private void logChanges(Snapshot prev, Snapshot next) {
        Set<String> added = new LinkedHashSet<>(next.entries.keySet());
        added.removeAll(prev.entries.keySet());
        Set<String> removed = new LinkedHashSet<>(prev.entries.keySet());
        removed.removeAll(next.entries.keySet());
        Set<String> changed = new LinkedHashSet<>();
        for (String name : next.entries.keySet()) {
            Entry prevEntry = prev.entries.get(name);
            if (prevEntry != null && prevEntry != next.entries.get(name)) {
                changed.add(name);
            }
        }
        boolean defaultChanged = !prev.defaultProvider().equals(next.defaultProvider());
        boolean rolesChanged = !prev.roles().equals(next.roles());

        if (added.isEmpty() && removed.isEmpty() && changed.isEmpty() && !defaultChanged && !rolesChanged) {
            log.debug("AI Provider 配置刷新：无变更");
            return;
        }
        log.info("AI Provider 配置已热更新：新增{} 移除{} 变更{}{}{}",
                added, removed, changed,
                defaultChanged ? " 默认切换为[" + next.defaultProvider() + "]" : "",
                rolesChanged ? " 角色路由" + next.roles() : "");
    }

    // ========================= 快照 =========================

    /**
     * Provider 运行态快照（不可变）
     * <p>
     * 每次配置刷新生成一个新快照整体替换，读路径（AiFacade 每次调用）无锁。
     * </p>
     */
    public static final class Snapshot {

        /** 已启用 provider 条目（name → 句柄+源配置），按配置声明序 */
        private final Map<String, Entry> entries;

        /** provider 视图（name → 实例），构造时预计算 */
        private final Map<String, AiProvider> providersView;

        /** 源配置视图（name → 配置），构造时预计算（模型目录等管理场景用） */
        private final Map<String, AiProperties.ProviderConfig> configsView;

        /** 默认 provider 名称 */
        private final String defaultProvider;

        /** 角色路由（角色 id → provider 名称） */
        private final Map<String, String> roles;

        private Snapshot(Map<String, Entry> entries, String defaultProvider, Map<String, String> roles) {
            this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
            Map<String, AiProvider> providers = new LinkedHashMap<>();
            Map<String, AiProperties.ProviderConfig> configs = new LinkedHashMap<>();
            this.entries.forEach((name, entry) -> {
                providers.put(name, entry.handle.provider());
                configs.put(name, entry.config);
            });
            this.providersView = Collections.unmodifiableMap(providers);
            this.configsView = Collections.unmodifiableMap(configs);
            this.defaultProvider = defaultProvider;
            this.roles = roles;
        }

        /** 全部已启用 provider（name → 实例） */
        public Map<String, AiProvider> providers() {
            return providersView;
        }

        /** 全部已启用 provider 的源配置（name → 配置） */
        public Map<String, AiProperties.ProviderConfig> providerConfigs() {
            return configsView;
        }

        /** 默认 provider 名称 */
        public String defaultProvider() {
            return defaultProvider;
        }

        /** 角色路由（角色 id → provider 名称） */
        public Map<String, String> roles() {
            return roles;
        }
    }

    /** 快照条目：运行态句柄 + 构建它的源配置（配置相等是实例复用的判据）。 */
    private static final class Entry {
        private final AiProviderFactory.ProviderHandle handle;
        private final AiProperties.ProviderConfig config;

        private Entry(AiProviderFactory.ProviderHandle handle, AiProperties.ProviderConfig config) {
            this.handle = handle;
            this.config = config;
        }
    }
}
