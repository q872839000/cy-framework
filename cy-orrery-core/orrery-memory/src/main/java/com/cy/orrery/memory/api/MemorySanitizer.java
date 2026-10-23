// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.api;

import com.cy.core.exception.CYException;

/**
 * 写入侧敏感信息过滤 SPI（默认正则实现；可整体替换为企业 DLP）。
 *
 * @author pcy
 */
public interface MemorySanitizer {

    /**
     * 校验文本可写入（命中敏感规则抛 50607）。
     *
     * @param text 文本
     * @throws CYException 命中敏感规则
     */
    void assertClean(String text);
}
