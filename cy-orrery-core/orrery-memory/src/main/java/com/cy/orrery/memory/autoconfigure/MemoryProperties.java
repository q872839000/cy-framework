// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.autoconfigure;

import com.cy.orrery.memory.semantic.RetrievalConfig;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记忆组件配置（cy.orrery.memory.*）。
 * <p>
 * 能力按子系统独立开关：L0 上下文永远在；画像 / L2 长期记忆 / L1 情景记忆
 * 各自 enabled 控制装配。提示词覆盖项指向资源路径（classpath:/file:）。
 * </p>
 *
 * @author pcy
 */
@Data
@ConfigurationProperties(prefix = "cy.orrery.memory")
public class MemoryProperties {

    /** 组件总开关。 */
    private boolean enabled = true;

    /** 雪花节点 ID（0-1023，多实例务必各配不同值）。 */
    private long nodeId = -1;

    /** 向量维度（由 embedding 模型决定，如 bge-m3=1024；L2/L1 必需）。 */
    private int vectorDimension = 0;

    /** 开发代建表（生产用手工执行 resources/orrery-memory/schema.sql）。 */
    private boolean jdbcAutoInit = false;

    /** L0 上下文窗口。 */
    private Context context = new Context();

    /** 画像。 */
    private Profile profile = new Profile();

    /** L2 长期语义记忆。 */
    private Semantic semantic = new Semantic();

    /** L1 情景记忆。 */
    private Episodic episodic = new Episodic();

    /** L1/L2 共用召回。 */
    private Retrieval retrieval = new Retrieval();

    /** 注入预算。 */
    private Injection injection = new Injection();

    /** 安全。 */
    private Security security = new Security();

    /** 治理清扫。 */
    private Purge purge = new Purge();

    /**
     * L0 上下文窗口配置。
     */
    @Data
    public static class Context {

        /** 模式：stateful（Redis 托管窗口）/ stateless（宿主自持消息列表）。 */
        private String mode = "stateful";

        /** 窗口 token 上限（≤0 关闭守望）。 */
        private int windowLimit = 32000;

        /** 常规档阈值（占窗口比例）。 */
        private double compressThreshold = 0.8;

        /** 硬档阈值（占窗口比例；0 = 不启用）。 */
        private double hardCompressThreshold = 0.92;

        /** 保留区轮次。 */
        private int recentKeepRounds = 3;

        /** 窗口与摘要 TTL（秒，滑动续期）。 */
        private long summaryTtlSeconds = 86400;

        /** 滚动摘要提示词覆盖（资源路径）。 */
        private String summaryPrompt;
    }

    /**
     * 画像配置。
     */
    @Data
    public static class Profile {

        /** 是否装配。 */
        private boolean enabled = false;

        /** 提取提示词骨架覆盖（维度段永远动态拼装，不可覆盖）。 */
        private String extractPrompt;
    }

    /**
     * L2 长期语义记忆配置。
     */
    @Data
    public static class Semantic {

        /** 是否装配（开启即要求 embedding + Milvus，缺则启动拒绝）。 */
        private boolean enabled = false;

        /** 每 scope ACTIVE 配额（≤0 不限）。 */
        private int quotaPerScope = 10000;

        /** 去重相似度阈值。 */
        private double dedupThreshold = 0.92;

        /** 热路径优先窗口（秒）。 */
        private long hotPathPriorityWindowSeconds = 600;

        /** 事实提取提示词覆盖。 */
        private String extractPrompt;

        /** 写入决策提示词覆盖。 */
        private String decisionPrompt;
    }

    /**
     * L1 情景记忆配置。
     */
    @Data
    public static class Episodic {

        /** 是否装配。 */
        private boolean enabled = false;

        /** 情景卡片 TTL（秒）。 */
        private long ttlSeconds = 604800;

        /** 情景沉淀提示词覆盖。 */
        private String settlePrompt;
    }

    /**
     * 召回配置。
     */
    @Data
    public static class Retrieval {

        /** 召回上限。 */
        private int topK = 8;

        /** 相似度权重。 */
        private double similarityWeight = 0.45;

        /** 重要度权重。 */
        private double importanceWeight = 0.2;

        /** 新鲜度权重。 */
        private double recencyWeight = 0.2;

        /** 使用频率权重。 */
        private double usageWeight = 0.1;

        /** 新鲜度半衰期（天）。 */
        private int recencyHalfLifeDays = 30;

        /**
         * 转召回配置记录。
         *
         * @return 召回配置
         */
        public RetrievalConfig toConfig() {
            return new RetrievalConfig(topK, similarityWeight, importanceWeight,
                    recencyWeight, usageWeight, recencyHalfLifeDays);
        }
    }

    /**
     * 注入预算配置。
     */
    @Data
    public static class Injection {

        /** 总预算（token）。 */
        private int tokenBudget = 2000;

        /** 画像段预算。 */
        private int profileBudget = 500;

        /** 相关记忆段预算。 */
        private int recalledBudget = 1000;

        /** 会话摘要段预算。 */
        private int sessionSummaryBudget = 500;
    }

    /**
     * 安全配置。
     */
    @Data
    public static class Security {

        /** 敏感信息拒写开关。 */
        private boolean sanitizerEnabled = true;
    }

    /**
     * 治理清扫配置。
     */
    @Data
    public static class Purge {

        /** 后台清扫开关（墓碑清理）。 */
        private boolean enabled = true;

        /** 清扫间隔（秒）。 */
        private long intervalSeconds = 3600;

        /** 墓碑保留期（秒）。 */
        private long tombstoneRetentionSeconds = 604800;
    }
}
