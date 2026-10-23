// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.context;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 上下文窗口快照（Redis 持久化单元：滚动摘要 + 窗口内未驱逐消息）。
 *
 * @author pcy
 */
@Data
public class ContextWindow {

    /** 乐观锁版本（并发写冲突检测）。 */
    private long version;

    /** 滚动摘要（窗口被驱逐内容的唯一留存）。 */
    private String summary;

    /** 窗口内消息（含折叠占位符状态）。 */
    private List<ChatMessage> messages = new ArrayList<>();

    /**
     * 空窗口（新版本从 0 起）。
     *
     * @return 空窗口
     */
    public static ContextWindow empty() {
        return new ContextWindow();
    }
}
