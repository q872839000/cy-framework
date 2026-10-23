// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.api;

/**
 * 记忆状态（作废不删除，保留墓碑供审计与追溯）。
 *
 * @author pcy
 */
public enum MemoryStatus {

    /** 生效中。 */
    ACTIVE,

    /** 被新记忆取代。 */
    SUPERSEDED,

    /** 被证伪或显式作废。 */
    INVALIDATED
}
