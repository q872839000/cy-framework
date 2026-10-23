// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.bridge.protocol;

import com.cy.orrery.protocol.event.AgentEvent;

/**
 * AgentEvent 下发端口（Agent 事件协议 v1 的宿主出口）。
 * <p>
 * 由宿主服务实现，把装配完毕的 {@link AgentEvent} 投递到对外通道
 * （SSE 连接 / Redis 广播 / MQ 主题等）。信封公共字段（type / channel / runId /
 * traceId / seq / terminal 等）已由 {@link AgentEventBridge} 赋值完毕——
 * 协议规定 seq 由<b>发射端</b>赋值且单调递增，本体系内的发射端即桥
 * （per-runId 计数器，一条运行时事件合成的多条协议事件序号互不相同）；
 * 实现方只做下发，不应再改动信封内容（含 seq）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/27
 */
public interface AgentEventPublisher {

    /**
     * 下发一条装配完毕的协议事件。
     *
     * @param event 协议事件信封（seq 已由桥按 per-run 单调计数赋值）
     */
    void publish(AgentEvent event);
}
