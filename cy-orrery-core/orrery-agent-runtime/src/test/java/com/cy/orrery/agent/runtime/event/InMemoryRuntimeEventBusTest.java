// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.event;


import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内事件总线单元测试。
 *
 * @author pcy
 * @since 2026/08/19
 */
class InMemoryRuntimeEventBusTest {

    /**
     * seq 单调递增且监听器收到全部事件。
     */
    @Test
    void shouldEmitMonotonicSeqAndNotifyListeners() {
        InMemoryRuntimeEventBus bus = new InMemoryRuntimeEventBus("run-1");
        List<RuntimeEvent> received = new ArrayList<>();
        bus.addListener(received::add);

        RuntimeEvent first = bus.emit(RuntimeEventType.RUN_STARTED);
        RuntimeEvent second = bus.emit(RuntimeEventType.SYSTEM_TRACE, Map.of("k", "v"));

        assertEquals(1, first.seq());
        assertEquals(2, second.seq());
        assertEquals(2, received.size());
        assertEquals("run-1", second.runId());
        assertEquals("v", second.data().get("k"));
        assertFalse(second.terminal());
    }

    /**
     * 终态事件标记 terminal。
     */
    @Test
    void shouldMarkTerminalFlag() {
        InMemoryRuntimeEventBus bus = new InMemoryRuntimeEventBus("run-1");
        RuntimeEvent completed = bus.emit(RuntimeEventType.RUN_COMPLETED);
        assertTrue(completed.terminal());
        assertTrue(RuntimeEventType.RUN_FAILED.terminal());
        assertFalse(RuntimeEventType.STEP_STARTED.terminal());
    }

    /**
     * 取消信号读写与监听器移除。
     */
    @Test
    void shouldSupportCancelAndRemoveListener() {
        InMemoryRuntimeEventBus bus = new InMemoryRuntimeEventBus("run-1");
        List<RuntimeEvent> received = new ArrayList<>();
        Consumer<RuntimeEvent> listener = received::add;
        bus.addListener(listener);

        assertFalse(bus.isCancelled());
        assertNull(bus.cancellationReason());
        bus.cancel("user request");
        assertTrue(bus.isCancelled());
        assertEquals("user request", bus.cancellationReason());

        assertTrue(bus.removeListener(listener));
        bus.emit(RuntimeEventType.RUN_STARTED);
        assertEquals(0, received.size());
    }

    /**
     * 监听器异常隔离：异常监听器不阻断后续监听器，也不向发射方传播。
     */
    @Test
    void shouldIsolateListenerFailure() {
        InMemoryRuntimeEventBus bus = new InMemoryRuntimeEventBus("run-1");
        List<RuntimeEvent> received = new ArrayList<>();
        bus.addListener(event -> {
            throw new IllegalStateException("listener boom");
        });
        bus.addListener(received::add);

        RuntimeEvent emitted = bus.emit(RuntimeEventType.RUN_STARTED);

        assertEquals(1, emitted.seq());
        assertEquals(1, received.size());
    }
}
