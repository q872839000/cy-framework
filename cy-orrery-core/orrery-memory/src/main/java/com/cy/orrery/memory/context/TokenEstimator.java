// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.context;

/**
 * Token 估算器（启发式：CJK 字符按 1 token，其余按 4 字符 1 token）。
 * <p>
 * 业务可替换为真实 tokenizer——替换点在 {@link MessageAdapter#estimateTokens}
 * 或 {@link ContextWindowConfig} 的调用方。
 * </p>
 *
 * @author pcy
 */
public final class TokenEstimator {

    /** 折叠判定用最小正文长度（字符；低于该值折叠收益低于占位成本）。 */
    private static final int MIN_FOLD_LENGTH = 128;

    /** 非 CJK 字符的 token 粗估比率（4 字符 ≈ 1 token）。 */
    private static final int NON_CJK_CHARS_PER_TOKEN = 4;

    private TokenEstimator() {
    }

    /**
     * 折叠判定用最小正文长度（字符；低于该值折叠收益低于占位成本）。
     *
     * @return 最小折叠长度
     */
    public static int minFoldLength() {
        return MIN_FOLD_LENGTH;
    }

    /**
     * 估算文本 token 数（null/空白为 0）。
     *
     * @param text 文本
     * @return token 估算
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + NON_CJK_CHARS_PER_TOKEN - 1) / NON_CJK_CHARS_PER_TOKEN;
    }

    /**
     * 是否 CJK 字符（含常用中文标点区）。
     *
     * @param c 字符
     * @return 是否 CJK
     */
    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }
}
