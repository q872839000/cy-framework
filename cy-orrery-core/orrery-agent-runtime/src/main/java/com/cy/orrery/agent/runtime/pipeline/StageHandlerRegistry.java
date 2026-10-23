// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阶段处理器注册表：按阶段标识索引分发。
 * <p>
 * 注册表在宿主装配期填充（阶段处理器集合固定）；运行期只读。
 * 重复注册视为装配错误（快速失败）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class StageHandlerRegistry {

    private final Map<String, StageHandler> handlers = new ConcurrentHashMap<>();

    /**
     * 注册阶段处理器（阶段标识重复时抛异常）。
     *
     * @param handler 处理器
     * @throws IllegalArgumentException 阶段标识已注册
     */
    public void register(StageHandler handler) {
        Objects.requireNonNull(handler, "handler");
        StageHandler previous = handlers.putIfAbsent(handler.stage(), handler);
        if (previous != null) {
            throw new IllegalArgumentException("阶段已注册处理器: " + handler.stage());
        }
    }

    /**
     * 查找阶段处理器。
     *
     * @param stage 阶段标识
     * @return 处理器；未注册返回 null
     */
    public StageHandler lookup(String stage) {
        return handlers.get(stage);
    }

    /**
     * 已注册的全部阶段标识。
     *
     * @return 阶段标识列表
     */
    public List<String> stages() {
        return List.copyOf(handlers.keySet());
    }
}
