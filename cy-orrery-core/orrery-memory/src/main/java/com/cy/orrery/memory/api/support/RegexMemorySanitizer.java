// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.api.support;

import com.cy.core.exception.CYException;
import com.cy.orrery.memory.api.MemorySanitizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 正则敏感信息过滤器（默认实现：密钥/凭证/常见 PII 模式命中即拒写）。
 * <p>
 * 企业级 DLP 通过注册 {@link MemorySanitizer} Bean 整体替换本实现。
 * </p>
 *
 * @author pcy
 */
public class RegexMemorySanitizer implements MemorySanitizer {

    /** 敏感模式（密钥/凭证/私钥/身份证/手机号）。 */
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)(api[_-]?key|secret|password|passwd|token)\\s*[:=]\\s*\\S{6,}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("\\b\\d{17}[\\dXx]\\b"),
            Pattern.compile("\\b1[3-9]\\d{9}\\b")
    );

    @Override
    public void assertClean(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(text).find()) {
                throw new CYException(50607, pattern.pattern());
            }
        }
    }
}
