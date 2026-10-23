// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Milvus 过滤表达式字面量转义测试（外部可控值防表达式注入）。
 *
 * @author pcy
 */
class MilvusMemoryVectorIndexTest {

    @Test
    void stringLiteralEscapesQuotesAndBackslashes() {
        assertEquals("\"user-1\"", MilvusMemoryVectorIndex.stringLiteral("user-1"));
        assertEquals("\"a\\\"b\"", MilvusMemoryVectorIndex.stringLiteral("a\"b"));
        assertEquals("\"a\\\\b\"", MilvusMemoryVectorIndex.stringLiteral("a\\b"));
        // 注入载荷：引号截断 + 逻辑运算拼接，转义后仅为普通字符串内容
        String payload = "x\" or owner_id == \"other";
        assertEquals("\"x\\\" or owner_id == \\\"other\"",
                MilvusMemoryVectorIndex.stringLiteral(payload));
    }
}
