// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.harness.impl;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.AttributeKey;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.event.RuntimeEventType;
import com.cy.orrery.agent.runtime.harness.ContextGuard;
import com.cy.orrery.agent.runtime.harness.ContextGuardConfig;
import com.cy.orrery.agent.runtime.harness.MessagePinner;
import com.cy.orrery.agent.runtime.harness.LoopTokenEstimator;
import com.cy.orrery.memory.context.ContextWindowConfig;
import com.cy.orrery.memory.context.ContextWindowManager;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上下文守望默认实现：高水位告警 + 双档折叠压缩。
 * <p>
 * 守望判定（高水位 / 双档阈值 / 保留轮次选择）与折叠执行委托 orrery-memory
 * 公共上下文子系统（{@link ContextWindowManager}，经 LoopMessageAdapter 适配），
 * 本类保留运行上下文接线（高水位告警每运行去重、折叠边界单调推进）与
 * SYSTEM_TRACE 事件上报。双档语义：超过常规阈值（compressThreshold × 窗口）
 * 折叠较早轮次、保留最近 recentKeepCount 轮；超过硬档阈值
 * （hardCompressThreshold × 窗口）折叠至最近 1 轮。折叠前经
 * {@link MessagePinner} 计算钉扎集合，活跃证据不丢失。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class DefaultContextGuard implements ContextGuard {

    private static final Logger log = LoggerFactory.getLogger(DefaultContextGuard.class);

    /** 高水位告警已发标记键名（检查点序列化器按此键做强类型注册）。 */
    public static final String HIGH_WATER_WARNED_KEY_NAME = "context.high_water_warned";

    private static final AttributeKey<Boolean> HIGH_WATER_WARNED =
            new AttributeKey<>(HIGH_WATER_WARNED_KEY_NAME, Boolean.class);

    private final MessagePinner pinner;
    private final ContextWindowManager<LoopMessage> windowManager;

    /**
     * 构造默认守望器（框架自有配置）。
     *
     * @param config 配置
     * @param pinner 钉扎 SPI（可为 {@link MessagePinner#NO_PIN}）
     */
    public DefaultContextGuard(ContextGuardConfig config, MessagePinner pinner) {
        this(toWindowConfig(config), pinner);
    }

    /**
     * 构造默认守望器（直接消费公共窗口配置）。
     * <p>
     * 自动装配经本构造注入 orrery-memory 的共享 {@code ContextWindowConfig} Bean
     * （配置面 {@code cy.orrery.memory.context.*}），消除窗口阈值双配置面。
     * </p>
     *
     * @param windowConfig 公共窗口配置
     * @param pinner       钉扎 SPI（可为 {@link MessagePinner#NO_PIN}）
     */
    public DefaultContextGuard(ContextWindowConfig windowConfig, MessagePinner pinner) {
        this.pinner = pinner == null ? MessagePinner.NO_PIN : pinner;
        this.windowManager = new ContextWindowManager<>(windowConfig, LoopMessageAdapter.INSTANCE);
    }

    @Override
    public int prepareForLlmCall(AgentRunContext ctx) {
        int estimated = LoopTokenEstimator.estimate(ctx.getMessages());
        warnIfNearLimit(ctx, estimated);
        maybeCompactContext(ctx, estimated);
        return LoopTokenEstimator.estimate(ctx.getMessages());
    }

    /**
     * 高水位告警（每运行一次；判定委托公共窗口管理器，守望关闭不告警）。
     *
     * @param ctx       运行上下文
     * @param estimated 当前估算 token
     */
    private void warnIfNearLimit(AgentRunContext ctx, int estimated) {
        if (Boolean.TRUE.equals(ctx.getAttr(HIGH_WATER_WARNED))) {
            return;
        }
        if (!windowManager.isHighWater(estimated)) {
            return;
        }
        ctx.putAttr(HIGH_WATER_WARNED, true);
        ctx.getBus().emit(RuntimeEventType.SYSTEM_TRACE, Map.of(
                "kind", "context.high_water",
                "message", "上下文 token 已接近限制",
                "current", estimated,
                "limit", windowManager.config().windowLimit(),
                "threshold", windowManager.config().normalThresholdTokens()));
        log.warn("Context high water runId={} current={} limit={}",
                ctx.getRunId(), estimated, windowManager.config().windowLimit());
    }

    /**
     * 双档折叠压缩（判定与折叠执行委托公共窗口管理器，钉扎经 SPI 注入）。
     *
     * @param ctx       运行上下文
     * @param estimated 当前估算 token
     */
    private void maybeCompactContext(AgentRunContext ctx, int estimated) {
        ContextWindowManager.CompactionResult result = windowManager.compactIfNeeded(
                ctx.getMessages(), ctx.getCompactionBoundary(), pinner::pinnedIndexes);
        if (!result.compacted()) {
            return;
        }
        ctx.setCompactionBoundary(result.newBoundary());
        reportCompaction(ctx, result.hardTier(), result.elided(), result.pinnedCount(),
                estimated, windowManager.config().normalThresholdTokens());
    }

    /**
     * 压缩结果上报（系统追踪事件 + 日志）。
     *
     * @param ctx       运行上下文
     * @param hardTier  是否硬保护档
     * @param elided    折叠消息数
     * @param pinned    钉扎保留数
     * @param estimated 当前估算 token
     * @param threshold 触发阈值
     */
    private void reportCompaction(AgentRunContext ctx, boolean hardTier, int elided,
                                  int pinned, int estimated, long threshold) {
        ctx.getBus().emit(RuntimeEventType.SYSTEM_TRACE, Map.of(
                "kind", "context.compacted",
                "message", hardTier ? "上下文已逼近窗口上限，硬保护档折叠至最近一轮"
                        : "上下文逼近上限，已折叠较早轮次的工具结果",
                "elided", elided,
                "pinned", pinned,
                "current", estimated,
                "threshold", threshold,
                "tier", hardTier ? "hard" : "normal"));
        log.info("Context compacted runId={} tier={} elided={} pinned={}",
                ctx.getRunId(), hardTier ? "hard" : "normal", elided, pinned);
    }

    /**
     * 守望配置转公共窗口配置。
     * <p>
     * 比例值经 {@link ContextGuardConfig#of} 校验必在合法区间；关闭态
     * （windowLimit≤0，或压缩阈值非法——仅绕过工厂直接构造记录时可能出现）
     * 以 limit=0 表达，比例占位值不参与判定。
     * </p>
     *
     * @param config 守望配置
     * @return 公共窗口配置
     */
    private static ContextWindowConfig toWindowConfig(ContextGuardConfig config) {
        boolean enabled = config.contextWindowLimit() > 0 && config.compressThreshold() > 0;
        double compressThreshold = config.compressThreshold() > 0 ? config.compressThreshold() : 0.8;
        double hardThreshold = Math.max(0, config.hardCompressThreshold());
        int keepRounds = Math.max(1, config.recentKeepCount());
        return new ContextWindowConfig(
                enabled ? config.contextWindowLimit() : 0,
                compressThreshold, hardThreshold, keepRounds);
    }
}
