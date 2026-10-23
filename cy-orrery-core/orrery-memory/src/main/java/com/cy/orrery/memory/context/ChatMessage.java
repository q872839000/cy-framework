// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上下文消息（组件托管窗口内的消息模型）。
 * <p>
 * 与宿主消息类型的换算由宿主在 append/prepareForLlm 边界完成；
 * 折叠后 {@code folded=true}、content 为占位符、{@code originalTokens}
 * 保留折叠前体量供审计与统计。
 * </p>
 *
 * @author pcy
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /** 角色：system / user / assistant / tool。 */
    private String role;

    /** 正文（折叠后为占位符）。 */
    private String content;

    /** 是否已折叠。 */
    private boolean folded;

    /** 折叠前 token 估算（未折叠为 0）。 */
    private int originalTokens;

    /** 角色：系统。 */
    public static final String ROLE_SYSTEM = "system";

    /** 角色：用户。 */
    public static final String ROLE_USER = "user";

    /** 角色：助手。 */
    public static final String ROLE_ASSISTANT = "assistant";

    /** 角色：工具。 */
    public static final String ROLE_TOOL = "tool";

    /**
     * 构造普通消息。
     *
     * @param role    角色
     * @param content 正文
     * @return 消息
     */
    public static ChatMessage of(String role, String content) {
        return ChatMessage.builder().role(role).content(content).build();
    }

    /**
     * 是否为 user/assistant 轮次消息（摘要原料）。
     *
     * @return 是否轮次消息
     */
    public boolean isTurnMessage() {
        return ROLE_USER.equals(role) || ROLE_ASSISTANT.equals(role);
    }

    /**
     * 是否可折叠（tool 消息且未折叠）。
     *
     * @return 是否可折叠
     */
    public boolean foldable() {
        return ROLE_TOOL.equals(role) && !folded;
    }
}
