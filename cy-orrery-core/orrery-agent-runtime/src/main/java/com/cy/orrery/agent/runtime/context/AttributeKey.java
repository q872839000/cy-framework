// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.context;

import java.util.Objects;

/**
 * 类型安全的上下文属性键。
 * <p>
 * 以「键名 + 值类型」二元组作为 {@link AgentRunContext} 属性表的键，
 * 取值时自动做类型收窄，避免裸字符串键的类型转换错误。
 * 相同键名的两个 AttributeKey 实例等价（equals / hashCode 基于键名）。
 * </p>
 *
 * @param <T> 属性值类型
 * @author pcy
 * @since 2026/08/19
 */
public final class AttributeKey<T> {

    private final String name;
    private final Class<T> type;

    /**
     * 构造属性键。
     *
     * @param name 键名（全局唯一约定，建议带模块前缀）
     * @param type 值类型
     */
    public AttributeKey(String name, Class<T> type) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
    }

    /**
     * 键名。
     *
     * @return 键名
     */
    public String name() {
        return name;
    }

    /**
     * 值类型。
     *
     * @return 值类型
     */
    public Class<T> type() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AttributeKey<?> that)) {
            return false;
        }
        return name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return "AttributeKey[" + name + "]";
    }
}
