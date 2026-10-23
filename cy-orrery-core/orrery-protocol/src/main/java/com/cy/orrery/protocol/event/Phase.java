// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 正文块相位（Agent 事件协议 v1）
 * <p>
 * 流式期间无法预知本轮是否会调用工具，正文（text）块的相位统一在 {@link EventPayload.StepFinished} 时结算：
 * <ul>
 *     <li>{@link #COMMENTARY} —— 引语：工具调用前的过渡叙述，归入所在 step 的折叠工作区，不作为正式答复；</li>
 *     <li>{@link #FINAL} —— 正式答复：终止轮（无工具调用）的正文，前端提升为答复气泡。</li>
 * </ul>
 * wire 取值通过 {@link JsonValue} 固化（{@code commentary} / {@code final}），与枚举名解耦。
 * </p>
 *
 * @author pcy
 * @date 2026/06/03
 */
public enum Phase {

    /** 引语：工具调用前的过渡叙述，不作为正式答复。 */
    COMMENTARY("commentary"),

    /** 正式答复：终止轮的正文，前端提升为答复气泡。 */
    FINAL("final");

    private final String wireName;

    Phase(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 对外 wire 名（写入 SSE / JSON 的稳定取值）。
     *
     * @return {@code "commentary"} / {@code "final"}
     */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * 宽松解析 wire 名，大小写不敏感；null 或无法识别返回 {@code null}。
     *
     * @param wireName wire 名
     * @return 相位枚举，解析失败返回 {@code null}
     */
    @JsonCreator
    public static Phase fromWire(String wireName) {
        if (wireName == null) {
            return null;
        }
        for (Phase p : values()) {
            if (p.wireName.equalsIgnoreCase(wireName)) {
                return p;
            }
        }
        return null;
    }
}
