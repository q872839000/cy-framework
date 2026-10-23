// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.config;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.event.RuntimeEvent;
import com.cy.orrery.agent.runtime.event.RuntimeEventType;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import com.cy.orrery.agent.runtime.harness.ContextGuard;
import com.cy.orrery.memory.autoconfigure.MemoryAutoConfiguration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动装配测试（上下文守望配置面收敛：优先消费 memory 共享窗口配置，
 * 记忆组件停用时回退框架自有配置）。
 *
 * @author pcy
 */
class OrreryAgentRuntimeAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MemoryAutoConfiguration.class, OrreryAgentRuntimeAutoConfiguration.class))
            .withPropertyValues("cy.orrery.memory.purge.enabled=false");

    @Test
    void contextGuardConsumesSharedMemoryWindowConfig() {
        runner.withPropertyValues("cy.orrery.memory.context.window-limit=16000")
                .run(context -> {
                    ContextGuard guard = context.getBean(ContextGuard.class);
                    // 高水位事件中的 limit 应来自 memory 配置面（16000），而非框架默认 32000
                    assertEquals(16000, highWaterLimitOf(guard));
                });
    }

    @Test
    void contextGuardFallsBackToOwnPropertiesWhenMemoryDisabled() {
        runner.withPropertyValues(
                        "cy.orrery.memory.enabled=false",
                        "cy.orrery.agent-runtime.context.window-limit=12000")
                .run(context -> {
                    ContextGuard guard = context.getBean(ContextGuard.class);
                    // 记忆组件停用（共享 Bean 不存在）→ 回退框架自有配置面
                    assertEquals(12000, highWaterLimitOf(guard));
                });
    }

    /**
     * 触发一次高水位判定并取出事件中的 limit（行为断言，不反射内部字段）。
     */
    private int highWaterLimitOf(ContextGuard guard) {
        List<RuntimeEvent> events = new ArrayList<>();
        List<LoopMessage> messages = new ArrayList<>(List.of(LoopMessage.system("sys")));
        // ASCII 按 4 字符 ≈ 1 token：80000 字符 ≈ 20000 token，足以越过各档阈值
        messages.add(LoopMessage.assistant("probe", null, null));
        messages.add(LoopMessage.tool("c1", "x".repeat(80000)));
        AgentRunContext ctx = new AgentRunContext("run-1",
                new InMemoryRuntimeEventBus("run-1"), null, messages);
        ctx.getBus().addListener(events::add);
        guard.prepareForLlmCall(ctx);
        for (RuntimeEvent event : events) {
            if (event.type() == RuntimeEventType.SYSTEM_TRACE
                    && "context.high_water".equals(event.data().get("kind"))) {
                Object limit = event.data().get("limit");
                assertTrue(limit instanceof Number);
                return ((Number) limit).intValue();
            }
        }
        throw new AssertionError("未捕获 context.high_water 事件");
    }
}
