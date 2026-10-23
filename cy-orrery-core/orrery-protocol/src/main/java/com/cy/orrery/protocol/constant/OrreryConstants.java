// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.constant;

/**
 * Orrery 平台公共常量
 * <p>
 * 集中管理协议版本、Redis 键前缀、默认超时、租约配置等常量，
 * 各模块通过引用此类保持一致。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
public final class OrreryConstants {

    private OrreryConstants() {
    }

    // ========== 协议版本 ==========

    /**
     * 当前协议版本号
     */
    public static final String PROTOCOL_VERSION = "1.0";

    // ========== 默认超时（毫秒） ==========

    /**
     * 默认调用超时时间（毫秒）
     */
    public static final long DEFAULT_INVOKE_TIMEOUT_MS = 30_000L;

    // ========== 重试策略默认值（RetryPolicy 模型引用） ==========

    /**
     * 默认最大重试次数
     */
    public static final int DEFAULT_MAX_RETRIES = 3;

    /**
     * 默认重试间隔（毫秒）
     */
    public static final long DEFAULT_RETRY_INTERVAL_MS = 1000L;

    // ========== Nacos 资产通道约定（发布方与订阅方共用） ==========

    /**
     * A2A 卡片 capabilities.extensions 中携带富语义目录副本的 extension URI。
     * Agent 注册与编排元数据的唯一权威通道是 Nacos A2A 卡片，完整 AgentDescriptor
     * 以该 extension 的 params.descriptorJson 携带。
     */
    public static final String AGENT_CARD_CATALOG_EXTENSION_URI = "orrery://catalog";

    /**
     * 战术知识热更通道的 Config group
     */
    public static final String TACTICS_GROUP = "TACTICS_GROUP";

    /**
     * Butler 提示词技能热更通道的 Config group（dataId 约定：清单
     * {@code skill--manifest.yaml} + 单技能 {@code skill--{name}.md}）
     */
    public static final String SKILLS_GROUP = "SKILLS_GROUP";
}
