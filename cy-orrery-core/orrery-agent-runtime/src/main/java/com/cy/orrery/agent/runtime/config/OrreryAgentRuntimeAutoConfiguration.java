// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.config;

import com.cy.orrery.agent.runtime.bridge.protocol.AgentEventBridge;
import com.cy.orrery.agent.runtime.bridge.protocol.AgentEventPublisher;
import com.cy.orrery.agent.runtime.checkpoint.CheckpointManager;
import com.cy.orrery.agent.runtime.checkpoint.CheckpointSaveFailureHook;
import com.cy.orrery.agent.runtime.checkpoint.CheckpointSerializer;
import com.cy.orrery.agent.runtime.checkpoint.CheckpointStore;
import com.cy.orrery.agent.runtime.checkpoint.impl.InMemoryCheckpointStore;
import com.cy.orrery.agent.runtime.checkpoint.impl.JacksonCheckpointSerializer;
import com.cy.orrery.agent.runtime.checkpoint.impl.RedisCheckpointStore;
import com.cy.orrery.agent.runtime.context.CancellationRegistry;
import com.cy.orrery.agent.runtime.context.impl.InMemoryCancellationRegistry;
import com.cy.orrery.agent.runtime.event.RuntimeEventBusFactory;
import com.cy.orrery.agent.runtime.harness.ContextGuard;
import com.cy.orrery.agent.runtime.harness.ContextGuardConfig;
import com.cy.orrery.agent.runtime.harness.MessagePinner;
import com.cy.orrery.agent.runtime.harness.impl.DefaultContextGuard;
import com.cy.orrery.agent.runtime.hitl.ApprovalDecisionStore;
import com.cy.orrery.agent.runtime.hitl.ApprovalGate;
import com.cy.orrery.agent.runtime.hitl.HitlCoordinator;
import com.cy.orrery.agent.runtime.hitl.impl.InMemoryApprovalDecisionStore;
import com.cy.orrery.agent.runtime.hitl.impl.InMemoryApprovalGate;
import com.cy.orrery.agent.runtime.hitl.impl.RedisApprovalGate;
import com.cy.orrery.agent.runtime.observe.MicrometerRuntimeObserver;
import com.cy.orrery.agent.runtime.pipeline.PipelineEngine;
import com.cy.orrery.agent.runtime.runner.AgentRunner;
import com.cy.orrery.memory.context.ContextWindowConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Orrery Agent 运行时自动配置。
 * <p>
 * 装配领域无关的基础设施 Bean：取消注册表、消息钉扎、上下文守望、阶段链引擎、
 * 审批等待门（memory / redis 按配置）、审批决定存储、检查点体系（序列化器 / 存储 /
 * 管理器）、事件总线工厂、HITL 协调器、运行门面；观测埋点（Micrometer）按类路径
 * 条件 + 全部单例就绪后挂载（不依赖 Bean 定义顺序），AgentEvent 线协议桥按配置
 * 开关装配。依赖宿主领域 SPI 的组件（{@code ReactLoopEngine} /
 * {@code LoopToolRoundEngine} / {@code GuardedToolInvoker} / LLM 装饰器）
 * 由宿主在装配层按领域依赖构造，不在此注册。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
@AutoConfiguration(afterName = {
        // ObjectMapper Bean 先于本配置评估（审批门 / 检查点序列化器按 Bean 存在性装配）
        "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration",
        // RedissonClient Bean 先于本配置评估：否则 gate/checkpoint=redis 时
        // @ConditionalOnBean(RedissonClient) 按定义顺序判 false → 无门可装配 → 启动失败。
        // Redisson 3.5x 起 starter 自动装配类为 V2，两个名字都挂上兼容旧版。
        "org.redisson.spring.starter.RedissonAutoConfiguration",
        "org.redisson.spring.starter.RedissonAutoConfigurationV2"})
@EnableConfigurationProperties(OrreryAgentRuntimeProperties.class)
public class OrreryAgentRuntimeAutoConfiguration {

    /**
     * 进程内取消注册表。
     *
     * @return 取消注册表
     */
    @Bean
    @ConditionalOnMissingBean(CancellationRegistry.class)
    public CancellationRegistry cancellationRegistry() {
        return new InMemoryCancellationRegistry();
    }

    /**
     * 消息钉扎默认实现（无钉扎语义）。
     *
     * @return 钉扎 SPI
     */
    @Bean
    @ConditionalOnMissingBean(MessagePinner.class)
    public MessagePinner messagePinner() {
        return MessagePinner.NO_PIN;
    }

    /**
     * 上下文守望默认实现（配置驱动）.
     * <p>
     * 消费 orrery-memory 自动装配的共享 {@code ContextWindowConfig} Bean
     * （配置面 {@code cy.orrery.memory.context.*}，单一配置源）；
     * 记忆组件整体停用（cy.orrery.memory.enabled=false，Bean 不存在）时回退到
     * 框架自有配置面 {@code cy.orrery.agent-runtime.context.*}。
     * </p>
     *
     * @param pinner     消息钉扎
     * @param sharedWindowConfig 共享窗口配置（memory 自动装配；可缺）
     * @param properties 运行时配置项（回退配置面来源）
     * @return 上下文守望器
     */
    @Bean
    @ConditionalOnMissingBean(ContextGuard.class)
    public ContextGuard contextGuard(MessagePinner pinner,
                                     ObjectProvider<ContextWindowConfig> sharedWindowConfig,
                                     OrreryAgentRuntimeProperties properties) {
        ContextWindowConfig shared = sharedWindowConfig.getIfAvailable();
        if (shared != null) {
            return new DefaultContextGuard(shared, pinner);
        }
        OrreryAgentRuntimeProperties.ContextSpec context = properties.getContext();
        return new DefaultContextGuard(ContextGuardConfig.of(context.getWindowLimit(),
                context.getCompressThreshold(), context.getHardCompressThreshold(),
                context.getRecentKeepCount()), pinner);
    }

    /**
     * 阶段链引擎（无状态单例）。
     *
     * @return 阶段链引擎
     */
    @Bean
    @ConditionalOnMissingBean(PipelineEngine.class)
    public PipelineEngine pipelineEngine() {
        return new PipelineEngine();
    }

    // ========================= HITL：审批门 / 决定存储 / 协调器 =========================

    /**
     * 审批门装配（approval.gate=memory 或缺省：进程内门）。
     *
     * @return 审批等待门
     */
    @Bean
    @ConditionalOnMissingBean(ApprovalGate.class)
    @ConditionalOnProperty(prefix = "cy.orrery.agent-runtime.approval", name = "gate",
            havingValue = "memory", matchIfMissing = true)
    public ApprovalGate inMemoryApprovalGate() {
        return new InMemoryApprovalGate();
    }

    /**
     * Redis 审批门装配（类路径存在 Redisson 时加载本组——redisson 是可选依赖，
     * 方法签名引用其类，必须类级 @ConditionalOnClass(name=) 防缺失时 NoClassDefFoundError）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.redisson.api.RedissonClient")
    static class RedisApprovalGateConfiguration {

        /**
         * 审批门装配（approval.gate=redis：跨实例唤醒 + 决定落 Redis 兜底；
         * 需宿主提供 RedissonClient 与 ObjectMapper）。销毁时自动反订阅（AutoCloseable）。
         *
         * @param redisson     Redisson 客户端
         * @param objectMapper JSON 编解码器
         * @param properties   运行时配置
         * @return 分布式审批等待门
         */
        @Bean
        @ConditionalOnMissingBean(ApprovalGate.class)
        @ConditionalOnBean(type = {"org.redisson.api.RedissonClient",
                "com.fasterxml.jackson.databind.ObjectMapper"})
        @ConditionalOnProperty(prefix = "cy.orrery.agent-runtime.approval", name = "gate",
                havingValue = "redis")
        public ApprovalGate redisApprovalGate(RedissonClient redisson, ObjectMapper objectMapper,
                                              OrreryAgentRuntimeProperties properties) {
            return new RedisApprovalGate(redisson, objectMapper,
                    RedisApprovalGate.DEFAULT_KEY_PREFIX, RedisApprovalGate.DEFAULT_TOPIC,
                    properties.getApproval().getDecisionTtlMillis());
        }
    }

    /**
     * 审批决定存储默认实现（「决定先于等待到达」兜底；多副本可换共享存储实现）。
     *
     * @return 审批决定存储
     */
    @Bean
    @ConditionalOnMissingBean(ApprovalDecisionStore.class)
    public ApprovalDecisionStore approvalDecisionStore() {
        return new InMemoryApprovalDecisionStore();
    }

    /**
     * HITL 协调门面（审批挂起 / 等待 / 注入一条龙，决定经存储兜底）。
     *
     * @param gate           审批等待门
     * @param decisionStore  审批决定存储
     * @return HITL 协调器
     */
    @Bean
    @ConditionalOnMissingBean(HitlCoordinator.class)
    public HitlCoordinator hitlCoordinator(ApprovalGate gate, ApprovalDecisionStore decisionStore) {
        return new HitlCoordinator(gate, decisionStore);
    }

    // ========================= 检查点体系 =========================

    /**
     * Jackson 检查点序列化器（复用宿主 ObjectMapper）。
     *
     * @param objectMapper JSON 编解码器
     * @return 检查点序列化器
     */
    @Bean
    @ConditionalOnBean(ObjectMapper.class)
    @ConditionalOnMissingBean(CheckpointSerializer.class)
    public CheckpointSerializer checkpointSerializer(ObjectMapper objectMapper) {
        return new JacksonCheckpointSerializer(objectMapper);
    }

    /**
     * 检查点存储装配（checkpoint.enabled 闸门，默认启用）。
     * <p>
     * {@code @ConditionalOnProperty} 不可重复标注，enabled / store 两个条件拆为
     * 类级 + 方法级两级：enabled=false 时整个存储层（含管理器与运行门面，
     * 经下游 @ConditionalOnBean 联动）不装配。
     * </p>
     *
     * @author pcy
     * @since 2026/08/27
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "cy.orrery.agent-runtime.checkpoint", name = "enabled",
            matchIfMissing = true)
    static class CheckpointStoreConfiguration {

        /**
         * 进程内检查点存储（默认；store=memory 时装配）。
         *
         * @return 检查点存储
         */
        @Bean
        @ConditionalOnMissingBean(CheckpointStore.class)
        @ConditionalOnProperty(prefix = "cy.orrery.agent-runtime.checkpoint", name = "store",
                havingValue = "memory", matchIfMissing = true)
        public CheckpointStore inMemoryCheckpointStore() {
            return new InMemoryCheckpointStore();
        }

        /**
         * Redis 检查点存储装配（类路径存在 Redisson 时加载本组——可选依赖防护，
         * 同 RedisApprovalGateConfiguration）。
         */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = "org.redisson.api.RedissonClient")
        static class RedisStoreConfiguration {

            /**
             * Redis 检查点存储（store=redis 且宿主提供 RedissonClient 时装配；
             * key 前缀 / 常规与挂起双 TTL 取配置）。
             *
             * @param redisson   Redisson 客户端
             * @param serializer 检查点序列化器
             * @param properties 运行时配置
             * @return 检查点存储
             */
            @Bean
            @ConditionalOnMissingBean(CheckpointStore.class)
            @ConditionalOnBean(type = "org.redisson.api.RedissonClient")
            @ConditionalOnProperty(prefix = "cy.orrery.agent-runtime.checkpoint", name = "store",
                    havingValue = "redis")
            public CheckpointStore redisCheckpointStore(RedissonClient redisson,
                                                        CheckpointSerializer serializer,
                                                        OrreryAgentRuntimeProperties properties) {
                OrreryAgentRuntimeProperties.CheckpointSpec spec = properties.getCheckpoint();
                return new RedisCheckpointStore(redisson, serializer,
                        spec.getKeyPrefix(), spec.getTtlMillis(), spec.getSuspendedTtlMillis());
            }
        }
    }

    /**
     * 检查点管理器（存在存储时装配）。
     *
     * @param store 检查点存储
     * @return 检查点管理器
     */
    @Bean
    @ConditionalOnBean(CheckpointStore.class)
    @ConditionalOnMissingBean(CheckpointManager.class)
    public CheckpointManager checkpointManager(CheckpointStore store) {
        return new CheckpointManager(store);
    }

    /**
     * 运行事件总线工厂（桥接 / 观测监听器的挂载点）。
     *
     * @return 事件总线工厂
     */
    @Bean
    @ConditionalOnMissingBean(RuntimeEventBusFactory.class)
    public RuntimeEventBusFactory runtimeEventBusFactory() {
        return new RuntimeEventBusFactory();
    }

    /**
     * Agent 运行门面（存在检查点管理器时装配；落盘档位取配置）。
     * <p>
     * 异步落盘失败钩子按 {@link CheckpointSaveFailureHook} 专用类型注入
     * （Micrometer 在类路径时由观测配置提供计数钩子，不在时为空——日志兜底
     * 始终有效；专用类型避免与宿主上下文中其它 {@code Consumer<Throwable>}
     * Bean 误绑）。
     * </p>
     *
     * @param busFactory    事件总线工厂
     * @param checkpoints   检查点管理器
     * @param hitl          HITL 协调器
     * @param cancellations 取消注册表
     * @param properties    运行时配置
     * @param saveFailureHookProvider 异步落盘失败钩子（可缺省）
     * @return 运行门面
     */
    @Bean
    @ConditionalOnBean(CheckpointManager.class)
    @ConditionalOnMissingBean(AgentRunner.class)
    public AgentRunner agentRunner(RuntimeEventBusFactory busFactory, CheckpointManager checkpoints,
                                   HitlCoordinator hitl, CancellationRegistry cancellations,
                                   OrreryAgentRuntimeProperties properties,
                                   ObjectProvider<CheckpointSaveFailureHook> saveFailureHookProvider) {
        return new AgentRunner(new AgentRunner.Dependencies(busFactory, checkpoints, hitl,
                cancellations, properties.getCheckpoint().getDurability(),
                saveFailureHookProvider.getIfAvailable()));
    }

    // ========================= Micrometer 观测 =========================

    /**
     * Micrometer 观测埋点装配。
     * <p>
     * 挂载延迟到 {@link SmartInitializingSingleton#afterSingletonsInstantiated}：
     * MeterRegistry 通常由 actuator / micrometer 自动配置注册，其 Bean 定义顺序
     * 不受本类控制——{@code @ConditionalOnBean(MeterRegistry)} 在本配置评估期
     * 常判 false 导致埋点静默丢失；改为 ObjectProvider 延迟解析 + 全部单例就绪后
     * getIfAvailable 挂载（装上 / 装不上各打一行 INFO），不再依赖 Bean 定义顺序。
     * </p>
     *
     * @author pcy
     * @since 2026/08/27
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MicrometerObserverConfiguration implements SmartInitializingSingleton {

        private static final Logger log = LoggerFactory.getLogger(MicrometerObserverConfiguration.class);

        private final ObjectProvider<MeterRegistry> registryProvider;
        private final ObjectProvider<RuntimeEventBusFactory> busFactoryProvider;
        private final ObjectProvider<MicrometerRuntimeObserver> observerProvider;
        private final ObjectProvider<AgentRunner> runnerProvider;

        MicrometerObserverConfiguration(ObjectProvider<MeterRegistry> registryProvider,
                                        ObjectProvider<RuntimeEventBusFactory> busFactoryProvider,
                                        ObjectProvider<MicrometerRuntimeObserver> observerProvider,
                                        ObjectProvider<AgentRunner> runnerProvider) {
            this.registryProvider = registryProvider;
            this.busFactoryProvider = busFactoryProvider;
            this.observerProvider = observerProvider;
            this.runnerProvider = runnerProvider;
        }

        /**
         * 异步检查点落盘失败计数钩子（MeterRegistry 缺失时为空钩子——日志兜底仍在 AgentRunner）。
         *
         * @param registryProvider 指标注册表（延迟解析）
         * @return 失败计数钩子
         */
        @Bean
        public CheckpointSaveFailureHook checkpointSaveFailureHook(
                ObjectProvider<MeterRegistry> registryProvider) {
            MeterRegistry registry = registryProvider.getIfAvailable();
            if (registry == null) {
                return failure -> {
                };
            }
            Counter counter = registry.counter("orrery.agent.checkpoint.save.failures");
            return failure -> counter.increment();
        }

        /**
         * 全部单例就绪后挂载观测：事件埋点监听器 + 活跃运行 gauge（装 / 不装各一行 INFO）。
         */
        @Override
        public void afterSingletonsInstantiated() {
            MeterRegistry registry = registryProvider.getIfAvailable();
            RuntimeEventBusFactory busFactory = busFactoryProvider.getIfAvailable();
            if (registry == null || busFactory == null) {
                log.info("Orrery Agent 运行时观测：MeterRegistry 不可用，Micrometer 埋点未挂载");
                return;
            }
            // 宿主自定义 observer 优先（getIfUnique），缺省自建
            MicrometerRuntimeObserver observer = observerProvider.getIfUnique();
            if (observer == null) {
                observer = new MicrometerRuntimeObserver(registry);
            }
            busFactory.addGlobalListener(observer);
            AgentRunner runner = runnerProvider.getIfAvailable();
            if (runner != null) {
                MicrometerRuntimeObserver.registerActiveRunsGauge(registry, runner);
            }
            log.info("Orrery Agent 运行时观测：Micrometer 埋点已挂载（事件指标 + active-runs gauge）");
        }
    }

    // ========================= AgentEvent 线协议桥 =========================

    /**
     * AgentEvent 线协议桥装配（桥启用时生效）。
     * <p>
     * 桥本身需要宿主提供 {@link AgentEventPublisher}（wire 事件发布通道）；
     * 注册动作延迟到全部单例就绪后执行，保证总线工厂已可用。
     * </p>
     *
     * @author pcy
     * @since 2026/08/27
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "cy.orrery.agent-runtime.bridge", name = "agent-event-enabled",
            matchIfMissing = true)
    static class AgentEventBridgeConfiguration {

        /**
         * AgentEvent 线协议桥（agentId 空串按 null 处理）。
         *
         * @param publisher  wire 事件发布通道（宿主 Bean）
         * @param properties 运行时配置
         * @return 线协议桥
         */
        @Bean
        @ConditionalOnBean(AgentEventPublisher.class)
        @ConditionalOnMissingBean(AgentEventBridge.class)
        public AgentEventBridge agentEventBridge(AgentEventPublisher publisher,
                                                 OrreryAgentRuntimeProperties properties) {
            String agentId = properties.getBridge().getAgentId();
            return new AgentEventBridge(publisher,
                    agentId == null || agentId.isEmpty() ? null : agentId);
        }

        /**
         * 线协议桥注册器：全部单例就绪后挂为事件总线工厂的全局监听器。
         *
         * @param busFactory 事件总线工厂
         * @param bridge     线协议桥
         * @return 注册器
         */
        @Bean
        @ConditionalOnBean(AgentEventBridge.class)
        public SmartInitializingSingleton agentEventBridgeRegistration(
                RuntimeEventBusFactory busFactory, AgentEventBridge bridge) {
            return () -> busFactory.addGlobalListener(bridge);
        }
    }
}
