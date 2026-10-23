// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.autoconfigure;

import com.cy.ai.AiFacade;
import com.cy.core.exception.CYException;
import com.cy.milvus.core.MilvusClientManager;
import com.cy.orrery.memory.admin.ForgettingEngine;
import com.cy.orrery.memory.admin.MemoryAdminService;
import com.cy.orrery.memory.api.ChatPort;
import com.cy.orrery.memory.api.EmbeddingPort;
import com.cy.orrery.memory.api.MemoryCapabilities;
import com.cy.orrery.memory.api.MemoryEventListener;
import com.cy.orrery.memory.api.MemorySanitizer;
import com.cy.orrery.memory.api.MemoryStore;
import com.cy.orrery.memory.api.VectorIndex;
import com.cy.orrery.memory.api.support.RegexMemorySanitizer;
import com.cy.orrery.memory.api.support.SnowflakeIdGenerator;
import com.cy.orrery.memory.assemble.MemoryContextAssembler;
import com.cy.orrery.memory.context.ContextManager;
import com.cy.orrery.memory.context.ContextWindowConfig;
import com.cy.orrery.memory.context.ContextWindowStore;
import com.cy.orrery.memory.context.InMemoryContextWindowStore;
import com.cy.orrery.memory.context.RedisContextWindowStore;
import com.cy.orrery.memory.context.RollingSummarizer;
import com.cy.orrery.memory.embedding.AiFacadeChatPort;
import com.cy.orrery.memory.embedding.AiFacadeEmbeddingPort;
import com.cy.orrery.memory.episodic.EpisodeService;
import com.cy.orrery.memory.episodic.EpisodeStore;
import com.cy.orrery.memory.episodic.RedisEpisodeStore;
import com.cy.orrery.memory.profile.JdbcProfileStore;
import com.cy.orrery.memory.profile.ProfileDimensionProvider;
import com.cy.orrery.memory.profile.ProfileExtractor;
import com.cy.orrery.memory.profile.ProfileService;
import com.cy.orrery.memory.profile.ProfileStore;
import com.cy.orrery.memory.prompt.PromptKey;
import com.cy.orrery.memory.prompt.PromptResolver;
import com.cy.orrery.memory.semantic.ConsolidationService;
import com.cy.orrery.memory.semantic.JdbcMemoryStore;
import com.cy.orrery.memory.semantic.MemoryDecider;
import com.cy.orrery.memory.semantic.MemoryExtractor;
import com.cy.orrery.memory.semantic.MilvusMemoryVectorIndex;
import com.cy.orrery.memory.semantic.SemanticMemoryService;
import com.cy.orrery.memory.tools.MemoryTools;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 记忆组件自动装配（按能力条件装配，未开启的子系统 Bean 不存在）。
 * <p>
 * 装配契约：L0 上下文永远在；画像/L2/L1 各自 enabled 控制。
 * L2 开启即要求 DataSource + Milvus + embedding 可用，缺一项启动拒绝（50623）
 * ——误配暴露在启动期而非运行期降级。
 * </p>
 *
 * @author pcy
 */
@AutoConfiguration(afterName = "com.cy.ai.config.AiAutoConfiguration")
@EnableConfigurationProperties(MemoryProperties.class)
@ConditionalOnProperty(prefix = "cy.orrery.memory", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MemoryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryAutoConfiguration.class);

    /** 发号器（node-id=-1 时按主机名哈希派生，多实例仍建议显式配置）。 */
    @Bean
    @ConditionalOnMissingBean
    public SnowflakeIdGenerator memoryIdGenerator(MemoryProperties properties) {
        long nodeId = properties.getNodeId();
        if (nodeId < 0) {
            nodeId = deriveNodeId();
            log.info("记忆组件雪花节点未显式配置，按主机名派生 nodeId={}（多实例部署请显式配置 cy.orrery.memory.node-id）",
                    nodeId);
        }
        return new SnowflakeIdGenerator(nodeId);
    }

    /** 提示词解析器（配置覆盖项在此注入）。 */
    @Bean
    @ConditionalOnMissingBean
    public PromptResolver memoryPromptResolver(MemoryProperties properties) {
        PromptResolver resolver = new PromptResolver();
        resolver.configureOverride(PromptKey.CONTEXT_SUMMARY, properties.getContext().getSummaryPrompt());
        resolver.configureOverride(PromptKey.FACT_EXTRACT, properties.getSemantic().getExtractPrompt());
        resolver.configureOverride(PromptKey.WRITE_DECISION, properties.getSemantic().getDecisionPrompt());
        resolver.configureOverride(PromptKey.PROFILE_EXTRACT, properties.getProfile().getExtractPrompt());
        resolver.configureOverride(PromptKey.EPISODE_SETTLE, properties.getEpisodic().getSettlePrompt());
        return resolver;
    }

    /** 窗口配置。 */
    @Bean
    @ConditionalOnMissingBean
    public ContextWindowConfig memoryContextWindowConfig(MemoryProperties properties) {
        MemoryProperties.Context c = properties.getContext();
        return new ContextWindowConfig(c.getWindowLimit(), c.getCompressThreshold(),
                c.getHardCompressThreshold(), c.getRecentKeepRounds());
    }

    /** LLM 对话端口与向量化端口（cy-common-ai 在场且 AiFacade Bean 已装配时才装配）。
     *  <p>
     *  {@code @ConditionalOnBean(AiFacade)} 按 Bean 定义顺序求值，故依赖类级
     *  {@code @AutoConfiguration(afterName = AiAutoConfiguration)} 保证 AiFacade
     *  先注册；AiFacade 自身有 {@code cy.ai.default-provider} 开关，未开启时
     *  本组 Bean 不装配（ChatPort / EmbeddingPort 缺席，相关能力自动降级）。
     *  </p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.cy.ai.AiFacade")
    @ConditionalOnBean(type = "com.cy.ai.AiFacade")
    static class AiBridgeConfiguration {

        /** LLM 对话端口。 */
        @Bean
        @ConditionalOnMissingBean(ChatPort.class)
        ChatPort memoryChatPort(AiFacade aiFacade) {
            return new AiFacadeChatPort(aiFacade);
        }

        /** 向量化端口（维度仅用于向量索引建集合校验；semantic/episodic 装配时会再校验）。 */
        @Bean
        @ConditionalOnMissingBean(EmbeddingPort.class)
        EmbeddingPort memoryEmbeddingPort(AiFacade aiFacade, MemoryProperties properties) {
            return new AiFacadeEmbeddingPort(aiFacade, properties.getVectorDimension());
        }
    }

    /** 敏感信息过滤器（security.sanitizer-enabled=false 时不装配；可注册 MemorySanitizer Bean 整体替换）。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cy.orrery.memory.security", name = "sanitizer-enabled",
            havingValue = "true", matchIfMissing = true)
    public MemorySanitizer memorySanitizer() {
        return new RegexMemorySanitizer();
    }

    /** 窗口存储（stateful 模式才装配：优先 Redis，无 Redis 降级内存并告警）。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cy.orrery.memory.context", name = "mode",
            havingValue = "stateful", matchIfMissing = true)
    public ContextWindowStore memoryContextWindowStore(MemoryProperties properties,
                                                       ObjectProvider<RedissonClient> redisson) {
        Duration ttl = Duration.ofSeconds(properties.getContext().getSummaryTtlSeconds());
        RedissonClient client = redisson.getIfAvailable();
        if (client != null) {
            return new RedisContextWindowStore(client, ttl);
        }
        log.warn("stateful 模式但无 RedissonClient——窗口降级为内存存储（重启丢失，仅开发可用）");
        return new InMemoryContextWindowStore();
    }

    /** 滚动摘要器。 */
    @Bean
    @ConditionalOnMissingBean
    public RollingSummarizer memoryRollingSummarizer(ObjectProvider<ChatPort> chatPort,
                                                     PromptResolver prompts) {
        return new RollingSummarizer(chatPort.getIfAvailable(), prompts);
    }

    /** 上下文管理器（L0 门面）。 */
    @Bean
    @ConditionalOnMissingBean
    public ContextManager memoryContextManager(ContextWindowConfig config,
                                               ObjectProvider<ContextWindowStore> store,
                                               RollingSummarizer summarizer) {
        return new ContextManager(config, store.getIfAvailable(), summarizer);
    }

    /** 能力视图。 */
    @Bean
    public MemoryCapabilities memoryCapabilities(ContextManager contextManager,
                                                 ObjectProvider<ProfileService> profile,
                                                 ObjectProvider<SemanticMemoryService> semantic,
                                                 ObjectProvider<EpisodeService> episodic,
                                                 ObjectProvider<ChatPort> chatPort,
                                                 ObjectProvider<EmbeddingPort> embeddingPort) {
        return MemoryCapabilities.builder()
                .contextAvailable(true)
                .windowPersistent(contextManager.windowPersistent())
                .profileAvailable(profile.getIfAvailable() != null)
                .semanticAvailable(semantic.getIfAvailable() != null)
                .episodicAvailable(episodic.getIfAvailable() != null)
                .chatAvailable(chatPort.getIfAvailable() != null)
                .embeddingAvailable(embeddingPort.getIfAvailable() != null)
                .build();
    }

    /** 注入装配器（缺席子系统段自动跳过）。 */
    @Bean
    @ConditionalOnMissingBean
    public MemoryContextAssembler memoryContextAssembler(MemoryProperties properties,
                                                         ObjectProvider<ProfileService> profile,
                                                         ObjectProvider<SemanticMemoryService> semantic,
                                                         ObjectProvider<EpisodeService> episodic) {
        MemoryProperties.Injection i = properties.getInjection();
        return new MemoryContextAssembler(
                new MemoryContextAssembler.InjectionBudget(i.getTokenBudget(), i.getProfileBudget(),
                        i.getRecalledBudget(), i.getSessionSummaryBudget()),
                profile.getIfAvailable(), semantic.getIfAvailable(), episodic.getIfAvailable());
    }

    /** 管理门面。 */
    @Bean
    @ConditionalOnMissingBean
    public MemoryAdminService memoryAdminService(ObjectProvider<SemanticMemoryService> semantic,
                                                 ObjectProvider<ProfileService> profile) {
        return new MemoryAdminService(semantic.getIfAvailable(), profile.getIfAvailable());
    }

    /** 遗忘引擎。 */
    @Bean
    @ConditionalOnMissingBean
    public ForgettingEngine memoryForgettingEngine(ObjectProvider<SemanticMemoryService> semantic,
                                                   ObjectProvider<ProfileService> profile) {
        return new ForgettingEngine(semantic.getIfAvailable(), profile.getIfAvailable());
    }

    /** 治理清扫调度（purge.enabled=true 时启动）。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "cy.orrery.memory.purge", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public MemoryPurgeScheduler memoryPurgeScheduler(
            MemoryProperties properties, ObjectProvider<SemanticMemoryService> semantic) {
        MemoryPurgeScheduler scheduler = new MemoryPurgeScheduler(properties, semantic);
        scheduler.start();
        return scheduler;
    }

    /** 表结构初始化（jdbc-auto-init=true 时启动期执行）。 */
    @Bean
    @ConditionalOnProperty(prefix = "cy.orrery.memory", name = "jdbc-auto-init", havingValue = "true")
    public MemorySchemaInitializer memorySchemaInitializer(ObjectProvider<DataSource> dataSource) {
        MemorySchemaInitializer initializer = new MemorySchemaInitializer(
                requireDataSource(dataSource, "jdbc-auto-init"));
        initializer.init();
        return initializer;
    }

    /**
     * 画像子系统装配（profile.enabled=true 且 DataSource 在场）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "cy.orrery.memory.profile", name = "enabled", havingValue = "true")
    @ConditionalOnClass(DataSource.class)
    static class ProfileConfiguration {

        /** 画像存储。 */
        @Bean
        @ConditionalOnMissingBean
        ProfileStore memoryProfileStore(ObjectProvider<DataSource> dataSource) {
            DataSource ds = requireDataSource(dataSource, "profile");
            return new JdbcProfileStore(new JdbcTemplate(ds),
                    new TransactionTemplate(new DataSourceTransactionManager(ds)));
        }

        /** 画像提取器。 */
        @Bean
        @ConditionalOnMissingBean
        ProfileExtractor memoryProfileExtractor(ObjectProvider<ChatPort> chatPort,
                                                PromptResolver prompts) {
            return new ProfileExtractor(chatPort.getIfAvailable(), prompts);
        }

        /** 画像服务（维度由消费方 Provider 合并注入）。 */
        @Bean
        @ConditionalOnMissingBean
        ProfileService memoryProfileService(ProfileStore store,
                                            ObjectProvider<List<ProfileDimensionProvider>> providers,
                                            ProfileExtractor extractor,
                                            SnowflakeIdGenerator idGenerator) {
            return new ProfileService(store, providers.getIfAvailable(List::of),
                    extractor, idGenerator, Clock.systemUTC());
        }
    }

    /**
     * L2 长期语义记忆装配（semantic.enabled=true；缺依赖启动拒绝）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "cy.orrery.memory.semantic", name = "enabled", havingValue = "true")
    static class SemanticConfiguration {

        /** 本体存储。 */
        @Bean
        @ConditionalOnMissingBean
        MemoryStore memoryStore(ObjectProvider<DataSource> dataSource) {
            DataSource ds = requireDataSource(dataSource, "semantic");
            return new JdbcMemoryStore(new JdbcTemplate(ds),
                    new TransactionTemplate(new DataSourceTransactionManager(ds)));
        }

        /** Milvus 向量索引（必需，缺 MilvusClientManager 启动拒绝）。 */
        @Bean
        @ConditionalOnMissingBean(VectorIndex.class)
        VectorIndex memoryVectorIndex(ObjectProvider<MilvusClientManager> milvus,
                                      MemoryProperties properties) {
            MilvusClientManager manager = milvus.getIfAvailable();
            if (manager == null) {
                throw new CYException(50623, "MilvusClientManager（检查 cy-common-milvus 装配与配置）");
            }
            if (properties.getVectorDimension() <= 0) {
                throw new CYException(50623, "cy.orrery.memory.vector-dimension 未配置");
            }
            return new MilvusMemoryVectorIndex(manager, properties.getVectorDimension());
        }

        /** 语义记忆服务（embedding 端口必需，缺失启动拒绝）。 */
        @Bean
        @ConditionalOnMissingBean
        SemanticMemoryService memorySemanticMemoryService(
                MemoryStore store, VectorIndex vectorIndex,
                ObjectProvider<EmbeddingPort> embeddingPort, MemoryProperties properties,
                SnowflakeIdGenerator idGenerator, ObjectProvider<MemorySanitizer> sanitizer,
                ObjectProvider<List<MemoryEventListener>> listeners) {
            EmbeddingPort embedding = embeddingPort.getIfAvailable();
            if (embedding == null || !embedding.available()) {
                throw new CYException(50623, "EmbeddingPort（检查 cy-common-ai embedding 配置与 "
                        + "cy.orrery.memory.vector-dimension）");
            }
            return new SemanticMemoryService(new SemanticMemoryService.Dependencies(
                    store, vectorIndex, embedding, properties.getRetrieval().toConfig(),
                    properties.getSemantic().getQuotaPerScope(), idGenerator,
                    sanitizer.getIfAvailable(), listeners.getIfAvailable(List::of),
                    Clock.systemUTC()));
        }

        /** 事实提取器。 */
        @Bean
        @ConditionalOnMissingBean
        MemoryExtractor memoryExtractor(ObjectProvider<ChatPort> chatPort, PromptResolver prompts) {
            return new MemoryExtractor(chatPort.getIfAvailable(), prompts);
        }

        /** 写入决策器。 */
        @Bean
        @ConditionalOnMissingBean
        MemoryDecider memoryDecider(ObjectProvider<ChatPort> chatPort, PromptResolver prompts) {
            return new MemoryDecider(chatPort.getIfAvailable(), prompts);
        }

        /** 巩固管线。 */
        @Bean
        @ConditionalOnMissingBean
        ConsolidationService memoryConsolidationService(
                SemanticMemoryService memoryService, MemoryExtractor extractor,
                MemoryDecider decider, VectorIndex vectorIndex, EmbeddingPort embeddingPort,
                MemoryProperties properties) {
            return new ConsolidationService(new ConsolidationService.Dependencies(
                    memoryService, extractor, decider, vectorIndex, embeddingPort,
                    properties.getSemantic().getDedupThreshold(),
                    properties.getSemantic().getHotPathPriorityWindowSeconds(),
                    Clock.systemUTC()));
        }

        /** Agent 工具门面。 */
        @Bean
        @ConditionalOnMissingBean
        MemoryTools memoryTools(SemanticMemoryService memoryService) {
            return new MemoryTools(memoryService);
        }
    }

    /**
     * L1 情景记忆装配（episodic.enabled=true；依赖 Redis + 向量索引 + embedding）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "cy.orrery.memory.episodic", name = "enabled", havingValue = "true")
    static class EpisodicConfiguration {

        /** 情景卡片存储。 */
        @Bean
        @ConditionalOnMissingBean
        EpisodeStore memoryEpisodeStore(ObjectProvider<RedissonClient> redisson,
                                        MemoryProperties properties) {
            RedissonClient client = redisson.getIfAvailable();
            if (client == null) {
                throw new CYException(50623, "RedissonClient（episodic 需要 Redis）");
            }
            return new RedisEpisodeStore(client,
                    Duration.ofSeconds(properties.getEpisodic().getTtlSeconds()));
        }

        /** 情景服务（向量索引与 embedding 必需，缺失启动拒绝）。 */
        @Bean
        @ConditionalOnMissingBean
        EpisodeService memoryEpisodeService(EpisodeStore store,
                                            ObjectProvider<VectorIndex> vectorIndex,
                                            ObjectProvider<EmbeddingPort> embeddingPort,
                                            ObjectProvider<ChatPort> chatPort,
                                            PromptResolver prompts) {
            VectorIndex index = vectorIndex.getIfAvailable();
            EmbeddingPort embedding = embeddingPort.getIfAvailable();
            if (index == null || embedding == null || !embedding.available()) {
                throw new CYException(50623, "VectorIndex/EmbeddingPort"
                        + "（episodic 需要 Milvus + embedding；检查 semantic 相关前置配置）");
            }
            return new EpisodeService(store, index, embedding,
                    chatPort.getIfAvailable(), prompts, Clock.systemUTC());
        }
    }

    /** DataSource 存在性断言。 */
    private static DataSource requireDataSource(ObjectProvider<DataSource> provider, String feature) {
        DataSource ds = provider.getIfAvailable();
        if (ds == null) {
            throw new CYException(50623, "DataSource（" + feature + " 需要 MySQL）");
        }
        return ds;
    }

    /** 按主机名派生节点 ID（解析失败归 0 并告警——单节点开发可容忍，多实例须显式配置）。 */
    private static long deriveNodeId() {
        try {
            return Math.floorMod(InetAddress.getLocalHost().getHostName().hashCode(), 1024);
        } catch (UnknownHostException e) {
            log.warn("主机名解析失败，雪花节点 ID 归 0（多实例部署请显式配置 cy.orrery.memory.node-id）: {}",
                    e.getMessage());
            return 0;
        }
    }
}
