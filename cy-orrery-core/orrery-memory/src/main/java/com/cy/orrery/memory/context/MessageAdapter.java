// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.context;

/**
 * 宿主消息适配器（组件与宿主消息类型之间的换算）。
 * <p>
 * 宿主消息通常是不可变记录，折叠经 {@link #fold} 返回替换实例，
 * 由折叠器写回列表原位。{@link #isFolded} 默认按占位符前缀识别。
 * </p>
 *
 * @param <T> 宿主消息类型
 * @author pcy
 */
public interface MessageAdapter<T> {

    /** 折叠占位符前缀。 */
    String FOLD_PLACEHOLDER_PREFIX = "[tool result, ";

    /**
     * 消息角色（system/user/assistant/tool 等）。
     *
     * @param message 宿主消息
     * @return 角色
     */
    String role(T message);

    /**
     * 消息正文。
     *
     * @param message 宿主消息
     * @return 正文
     */
    String content(T message);

    /**
     * 重建消息（折叠替换正文；保留 role/toolCallId 等配对字段）。
     *
     * @param message    宿主消息
     * @param newContent 新正文
     * @return 替换实例
     */
    T withContent(T message, String newContent);

    /**
     * 附加 token（如 assistant 消息的 tool_calls 参数）。
     *
     * @param message 宿主消息
     * @return 附加 token 数
     */
    default int extraTokens(T message) {
        return 0;
    }

    /**
     * 估算 token 数（正文 + 附加）。
     *
     * @param message 宿主消息
     * @return token 估算
     */
    default int estimateTokens(T message) {
        return TokenEstimator.estimate(content(message)) + extraTokens(message);
    }

    /**
     * 是否已被折叠（默认按占位符前缀识别）。
     *
     * @param message 宿主消息
     * @return 已折叠
     */
    default boolean isFolded(T message) {
        String content = content(message);
        return content != null && content.startsWith(FOLD_PLACEHOLDER_PREFIX);
    }

    /**
     * 折叠（默认：正文替换为长度占位符）。
     *
     * @param message       宿主消息
     * @param originalChars 折叠前字符数
     * @return 替换实例
     */
    default T fold(T message, int originalChars) {
        return withContent(message, FOLD_PLACEHOLDER_PREFIX + originalChars + " chars elided]");
    }
}
