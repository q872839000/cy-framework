// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.api;

/**
 * 记忆归属范围类型。
 * <p>
 * 归属是每条记忆的过滤属性（召回时按 scope 过滤），不再作为分层依据。
 * </p>
 *
 * @author pcy
 */
public enum ScopeType {

    /** 会话级：单次会话内有效。 */
    SESSION,

    /** 用户级：归属某个登录用户，跨会话跨 Agent 生效。 */
    USER,

    /** Agent 级：归属某个 Agent 自身。 */
    AGENT,

    /** 租户级：平台/租户共享。 */
    TENANT
}
