// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 事件频道（Agent 事件协议 v1）
 * <p>
 * 频道是事件在客户端的语义投递分组：
 * <ul>
 *     <li>{@link #USER} —— 业务前端必须处理的语义事件，任何调试级别都下发；</li>
 *     <li>{@link #DEBUG} —— 开发 / 运维向的诊断事件，按 {@link DebugLevel} 与事件类型的
 *         {@link EventType#minLevel()} 阈值过滤。</li>
 * </ul>
 * 某事件归属哪个频道由 {@link EventType#channel()} 唯一决定，业务代码不手工指定，避免“同一事件
 * 在不同发射点写不同频道”的不一致。wire 取值（{@code user} / {@code debug}）通过 {@link JsonValue}
 * 固化，与 Java 枚举名解耦——重命名枚举常量不会改变对外协议。
 * </p>
 *
 * @author pcy
 * @date 2026/06/03
 */
public enum Channel {

    /** 用户频道：业务语义事件，始终下发。 */
    USER("user"),

    /** 调试频道：诊断事件，按调试级别过滤。 */
    DEBUG("debug");

    private final String wireName;

    Channel(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 对外 wire 名（写入 SSE / JSON 的稳定取值），与枚举常量名解耦。
     *
     * @return {@code "user"} / {@code "debug"}
     */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * 宽松解析 wire 名，大小写不敏感；null 或无法识别返回 {@code null}。
     *
     * @param wireName wire 名
     * @return 频道枚举，解析失败返回 {@code null}
     */
    @JsonCreator
    public static Channel fromWire(String wireName) {
        if (wireName == null) {
            return null;
        }
        for (Channel c : values()) {
            if (c.wireName.equalsIgnoreCase(wireName)) {
                return c;
            }
        }
        return null;
    }
}
