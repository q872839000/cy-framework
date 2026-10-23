// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.event;

/**
 * 事件可见性策略（Agent 事件协议 v1）
 * <p>
 * 把“某事件在某调试级别下是否下发给客户端”的判定集中到一处，供服务端的各条投递路径共用，保证策略一致：
 * <ul>
 *     <li>实时投递路径：持有强类型 {@link AgentEvent}，传入其 {@link AgentEvent#type()}；</li>
 *     <li>续传 / 中继路径：只持有信封的 {@code type} 字符串，经 {@link #visible(String, DebugLevel)}
 *         还原为 {@link EventType} 后复用同一套规则。</li>
 * </ul>
 * </p>
 * <p>
 * 判定逻辑下沉到 {@link EventType#minLevel()}：用户频道事件其 {@code minLevel} 为 {@link DebugLevel#OFF}，
 * 任何级别都下发；调试频道事件按各自 {@code minLevel} 阈值过滤。以枚举为单一事实源——新增 / 重命名事件类型
 * 不会让可见性策略悄然失配，杜绝手抄事件类型名导致的误放行 / 误拦截。
 * </p>
 *
 * @author pcy
 * @date 2026/06/01
 */
public final class EventVisibility {

    private EventVisibility() {
    }

    /**
     * 判定事件是否应下发给指定调试级别的客户端。
     *
     * @param type  事件类型；{@code null} 或 {@link EventType#UNKNOWN} 保守丢弃
     * @param level 客户端调试级别；{@code null} 视为 {@link DebugLevel#OFF}
     * @return 是否放行
     */
    public static boolean visible(EventType type, DebugLevel level) {
        // null（无类型）与 UNKNOWN（无法识别的类型）都保守丢弃:无法判定其频道 / 级别归属时,
        // 宁可不下发也不误放行。注意 fromWire 对未知 wire 名归一到 UNKNOWN 是给“反序列化的消费者”
        // 一个显式信号,与“服务端是否向客户端下发”是两件事。
        if (type == null || type == EventType.UNKNOWN) {
            return false;
        }
        DebugLevel effective = level == null ? DebugLevel.OFF : level;
        return effective.includes(type.minLevel());
    }

    /**
     * 字符串重载：续传 / 中继路径只持有信封 {@code type} 字符串时使用。未知类型保守丢弃，
     * 避免服务端新增调试事件时因策略缺失而意外泄露。
     *
     * @param wireType SSE event 类型名（= {@link EventType#wireName()} = 信封 type）
     * @param level    客户端调试级别
     * @return 是否放行
     */
    public static boolean visible(String wireType, DebugLevel level) {
        return visible(EventType.fromWire(wireType), level);
    }
}
