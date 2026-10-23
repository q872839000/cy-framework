// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.intent;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 意图路由器单元测试（分发 / 兜底 / 重复注册快速失败 / routable 判定）。
 *
 * @author pcy
 * @since 2026/08/19
 */
class IntentRouterTest {

    private AgentRunContext newContext() {
        return new AgentRunContext("run-1", new InMemoryRuntimeEventBus("run-1"), null, null);
    }

    private static IntentStrategy<String> strategy(String name, String result) {
        return new IntentStrategy<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String execute(AgentRunContext ctx) {
                return result;
            }
        };
    }

    /**
     * 按意图键分发到已注册策略。
     */
    @Test
    void shouldDispatchToRegisteredStrategy() {
        IntentRouter<String> router = new IntentRouter<>(null);
        router.register(strategy("react", "REACT_RESULT"));

        assertEquals("REACT_RESULT", router.dispatch(newContext(), "react"));
    }

    /**
     * 未注册意图键落兜底策略。
     */
    @Test
    void shouldFallbackForUnknownKey() {
        IntentRouter<String> router = new IntentRouter<>(strategy("default", "FALLBACK"));
        router.register(strategy("react", "REACT_RESULT"));

        assertEquals("FALLBACK", router.dispatch(newContext(), "ghost"));
        assertEquals("FALLBACK", router.dispatch(newContext(), null));
    }

    /**
     * 无兜底且未注册时快速失败（装配错误暴露在首跑）。
     */
    @Test
    void shouldFailFastWithoutFallback() {
        IntentRouter<String> router = new IntentRouter<>(null);
        router.register(strategy("react", "REACT_RESULT"));

        assertThrows(IllegalStateException.class, () -> router.dispatch(newContext(), "ghost"));
    }

    /**
     * 重复注册快速失败。
     */
    @Test
    void shouldRejectDuplicateRegistration() {
        IntentRouter<String> router = new IntentRouter<>(null);
        router.register(strategy("react", "A"));

        assertThrows(IllegalArgumentException.class, () -> router.register(strategy("react", "B")));
    }

    /**
     * routable 判定：已注册或有兜底即可分发。
     */
    @Test
    void shouldCheckRoutable() {
        IntentRouter<String> router = new IntentRouter<>(strategy("default", "FALLBACK"));
        router.register(strategy("react", "REACT_RESULT"));

        assertTrue(router.routable("react"));
        assertTrue(router.routable("ghost"));
        assertFalse(new IntentRouter<String>(null).routable("ghost"));
    }
}
