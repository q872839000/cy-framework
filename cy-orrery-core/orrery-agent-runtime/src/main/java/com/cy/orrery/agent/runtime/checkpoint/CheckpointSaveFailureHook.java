// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.checkpoint;

import java.util.function.Consumer;

/**
 * 异步检查点落盘失败钩子（注入类型标识）。
 * <p>
 * 以专用接口继承 {@code Consumer<Throwable>}，避免自动装配按裸 {@code Consumer} 类型
 * 注入时误绑宿主上下文中其它同类型 Bean。观测实现（如 Micrometer 计数钩子）
 * 经自动装配注册；宿主自定义时声明本类型 Bean 即可被运行门面接收。
 * </p>
 *
 * @author pcy
 * @since 2026/09/29
 */
@FunctionalInterface
public interface CheckpointSaveFailureHook extends Consumer<Throwable> {
}
