// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.prompt;

/**
 * LLM 输出清洗（去掉 markdown 围栏与首尾噪声，供各解析器共用）。
 *
 * @author pcy
 */
public final class LlmOutput {

    private LlmOutput() {
    }

    /**
     * 去掉 ```json 围栏与首尾空白。
     *
     * @param output 模型输出
     * @return 清洗后文本
     */
    public static String stripFences(String output) {
        if (output == null) {
            return "";
        }
        String s = output.strip();
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (first > 0 && last > first) {
                s = s.substring(first + 1, last).strip();
            }
        }
        return s;
    }
}
