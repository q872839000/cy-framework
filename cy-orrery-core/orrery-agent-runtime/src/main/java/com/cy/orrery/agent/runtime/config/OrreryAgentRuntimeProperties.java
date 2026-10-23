// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.config;

import com.cy.orrery.agent.runtime.checkpoint.Durability;
import com.cy.orrery.agent.runtime.checkpoint.impl.RedisCheckpointStore;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Orrery Agent 运行时配置项。
 * <p>
 * 引擎实例（{@code ReactLoopEngine} / {@code LoopToolRoundEngine}）由宿主按领域依赖
 * 构造，本配置只驱动自动装配的领域无关 Bean（取消注册表 / 上下文守望 / 阶段链引擎 /
 * 审批门）与宿主组装 harness 时读取的默认值。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
@Data
@ConfigurationProperties(prefix = "cy.orrery.agent-runtime")
public class OrreryAgentRuntimeProperties {

    /** ReAct 循环引擎配置。 */
    private LoopSpec loop = new LoopSpec();

    /** 阶段链引擎配置。 */
    private PipelineSpec pipeline = new PipelineSpec();

    /** 工具 harness 配置。 */
    private ToolSpec tool = new ToolSpec();

    /** LLM 调用 harness 配置。 */
    private LlmSpec llm = new LlmSpec();

    /** 检查点持久化配置。 */
    private CheckpointSpec checkpoint = new CheckpointSpec();

    /** HITL 审批门配置。 */
    private ApprovalSpec approval = new ApprovalSpec();

    /** AgentEvent 线协议桥配置。 */
    private BridgeSpec bridge = new BridgeSpec();

    /** Agent 身份发布配置（Nacos A2A 卡片 + 端点，租约 + 心跳保活）。 */
    private IdentitySpec identity = new IdentitySpec();

    /** 提示词资产配置（Nacos Prompt Registry 只读适配 + classpath 兜底）。 */
    private AssetsSpec assets = new AssetsSpec();

    /** 上下文守望回退配置（memory 组件停用时的自有配置面）。 */
    private ContextSpec context = new ContextSpec();

    /**
     * 上下文守望回退配置（仅 orrery-memory 停用、共享 ContextWindowConfig Bean
     * 不存在时生效；默认值与 {@code ContextGuardConfig.defaults()} 对齐，
     * 不改变既有部署行为）。
     */
    @Data
    public static class ContextSpec {

        /** 上下文窗口 token 上限（≤0 关闭守望）。 */
        private int windowLimit = 32000;

        /** 常规压缩阈值（0, 1]。 */
        private double compressThreshold = 0.8;

        /** 硬压缩阈值（0, 1]；0 关闭硬档。 */
        private double hardCompressThreshold = 0.92;

        /** 折叠时保留的最近轮数（≥1）。 */
        private int recentKeepCount = 3;
    }

    /**
     * 提示词资产配置。
     */
    @Data
    public static class AssetsSpec {

        /** 是否启用 Nacos Prompt 通道（默认关=纯 classpath，行为与旧版等价）。 */
        private boolean enabled = false;

        /** 读取标签（stable / canary / latest；灰度按进程选标签，不支持按请求分流）。 */
        private String label = "stable";

        /** 资产 Key 首段 agentId（空 = 取宿主声明的 AgentDescriptor Bean 的 agentId）。 */
        private String agentId = "";
    }

    /**
     * Agent 身份发布配置。
     */
    @Data
    public static class IdentitySpec {

        /** 是否启用身份发布（默认关，宿主显式打开）。 */
        private boolean enabled = false;

        /** 目标命名空间 ID（空 = Nacos 默认 public）。 */
        private String namespace = "";

        /** 优雅注销（进程退出时摘除 A2A 端点并把卡片置为非最新）。 */
        private boolean gracefulDeregister = true;

        /** 卡片/端点发布失败的最大重试次数（指数退避）。 */
        private int maxRetries = 3;

        /** 发布首次重试间隔（毫秒）。 */
        private long retryIntervalMillis = 1000L;

        /**
         * 心跳续期间隔（秒）：周期重发卡片 + 端点。
         * A2A 端点是租约模型——续期=在线、不续期=过期下线（崩溃即不再续期，自动摘除）。
         */
        private int heartbeatIntervalSeconds = 30;

        /** Nacos 地址覆盖（空 = 读 spring.cloud.nacos.server-addr）。 */
        private String serverAddr = "";

        /** Nacos 用户名覆盖（空 = 读 spring.cloud.nacos.username）。 */
        private String username = "";

        /** Nacos 密码覆盖（空 = 读 spring.cloud.nacos.password）。 */
        private String password = "";
    }

    /**
     * ReAct 循环引擎配置。
     */
    @Data
    public static class LoopSpec {

        /** 最大轮次。 */
        private int maxRounds = 25;

        /** 退化熔断阈值（连续「工具失败且零业务进展」轮数，≥2）。 */
        private int maxConsecutiveFailedRounds = 3;

        /** 收敛熔断阈值（连续「零事实进展」无工具调用轮数，≥2）。 */
        private int maxConsecutiveIdleRounds = 3;
    }

    /**
     * 阶段链引擎配置。
     */
    @Data
    public static class PipelineSpec {

        /** 最大阶段转移次数（熔断上限）。 */
        private int maxTransitions = 50;

        /** 最大回退次数（回退预算）。 */
        private int maxRollbacks = 5;
    }

    /**
     * 工具 harness 配置（GuardedToolInvoker 组装参数）。
     */
    @Data
    public static class ToolSpec {

        /** 单次工具执行超时毫秒（超时强取消）。 */
        private long timeoutMillis = 30000L;

        /** 相同参数指纹熔断阈值（连续失败次数；≤0 关闭）。 */
        private int argFuseThreshold = 2;

        /** 单工具连续失败升级阈值（≤0 关闭）。 */
        private int failureStreakThreshold = 3;
    }

    /**
     * LLM 调用 harness 配置（重试 / 续接装饰器组装参数）。
     */
    @Data
    public static class LlmSpec {

        /** 瞬时失败最大重试次数（不含首次；0 关闭重试）。 */
        private int retryMaxRetries = 2;

        /** 重试退避基准毫秒（指数退避 base）。 */
        private long retryBackoffBaseMillis = 800L;

        /** 截断自动续接最大轮数（≥1）。 */
        private int truncationMaxContinuations = 2;
    }

    /**
     * 检查点持久化配置（检查点存储 / 落盘档位）。
     */
    @Data
    public static class CheckpointSpec {

        /** 是否启用检查点（false 时不装配存储 / 管理器 / 运行门面）。 */
        private boolean enabled = true;

        /** 落盘时机档位（SYNC / ASYNC / EXIT，默认 ASYNC 均衡档）。 */
        private Durability durability = Durability.ASYNC;

        /** 存储类型：memory（进程内，默认）/ redis（多副本共享）。 */
        private String store = "memory";

        /** Redis 存储 key 前缀（store=redis 时生效）。 */
        private String keyPrefix = RedisCheckpointStore.DEFAULT_KEY_PREFIX;

        /** Redis 常规检查点 TTL 毫秒（store=redis 时生效；≤0 表示不过期）。 */
        private long ttlMillis = RedisCheckpointStore.DEFAULT_TTL_MILLIS;

        /**
         * Redis 挂起检查点 TTL 毫秒（store=redis 时生效；≤0 表示不过期）。
         * 属性表含挂起态契约（审批 / 澄清 / 阶段等待）的检查点用本 TTL——
         * 审批等待可能横跨数天，须比常规 TTL 长。
         */
        private long suspendedTtlMillis = RedisCheckpointStore.DEFAULT_SUSPENDED_TTL_MILLIS;
    }

    /**
     * HITL 审批门配置。
     */
    @Data
    public static class ApprovalSpec {

        /** 审批门类型：memory（进程内，默认）/ redis（多副本跨实例唤醒）。 */
        private String gate = "memory";

        /**
         * Redis 审批决定 TTL 毫秒（gate=redis 时生效；≤0 表示不过期）。
         * 与挂起检查点 TTL 对齐：决定必须活到它要唤醒的挂起运行（默认 7 天 =
         * 604800000ms；用字面量而非算式——配置元数据处理器无法求值表达式，
         * 算式会让 IDE 补全展示错误默认值 0）。
         */
        private long decisionTtlMillis = 604800000L;
    }

    /**
     * 线协议桥配置（事件总线 → 宿主 wire 事件）。
     */
    @Data
    public static class BridgeSpec {

        /** 上报注册中心的 Agent ID（空串表示未指定，桥以 null 处理）。 */
        private String agentId = "";

        /** 是否启用 AgentEvent 线协议桥。 */
        private boolean agentEventEnabled = true;
    }
}
