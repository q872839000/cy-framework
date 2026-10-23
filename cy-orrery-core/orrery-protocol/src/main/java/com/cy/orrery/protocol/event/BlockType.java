// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 流式块类型（Agent 事件协议 v1）
 * <p>
 * 模型流式输出按“块”切分，块类型决定前端的渲染泳道：
 * <ul>
 *     <li>{@link #REASONING} —— 思维链（模型 {@code reasoning_content}），走用户频道、前端默认折叠；</li>
 *     <li>{@link #COMMENTARY} —— 过程旁白（工具循环中途的模型 {@code content}），走用户频道、前端默认折叠，
 *         与思维链同属过程信息，不作正式答复；</li>
 *     <li>{@link #TEXT} —— 正文（收尾答复 / 查询答案），走用户频道、前端常态展示，是本轮唯一面向用户的产品。</li>
 * </ul>
 * 过程（{@code REASONING} / {@code COMMENTARY}）与产品（{@code TEXT}）在发射时即按类型分流，
 * 前端不必依赖步骤相位回溯判定某段正文是旁白还是答复。
 * wire 取值通过 {@link JsonValue} 固化（{@code reasoning} / {@code commentary} / {@code text}），与枚举名解耦。
 * </p>
 *
 * @author pcy
 * @date 2026/06/03
 */
public enum BlockType {

    /** 思维链块（模型 reasoning_content）。 */
    REASONING("reasoning"),

    /** 过程旁白块（工具循环中途的模型 content）：与思维链同属过程信息，前端默认折叠，不作正式答复。 */
    COMMENTARY("commentary"),

    /** 正文块（收尾答复 / 查询答案）：本轮唯一面向用户的产品。 */
    TEXT("text");

    private final String wireName;

    BlockType(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 对外 wire 名（写入 SSE / JSON 的稳定取值）。
     *
     * @return {@code "reasoning"} / {@code "commentary"} / {@code "text"}
     */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * 宽松解析 wire 名，大小写不敏感；null 或无法识别返回 {@code null}。
     *
     * @param wireName wire 名
     * @return 块类型枚举，解析失败返回 {@code null}
     */
    @JsonCreator
    public static BlockType fromWire(String wireName) {
        if (wireName == null) {
            return null;
        }
        for (BlockType t : values()) {
            if (t.wireName.equalsIgnoreCase(wireName)) {
                return t;
            }
        }
        return null;
    }
}
