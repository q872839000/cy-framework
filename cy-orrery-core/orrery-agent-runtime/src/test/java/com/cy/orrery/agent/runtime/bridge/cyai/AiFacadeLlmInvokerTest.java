// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.bridge.cyai;


import com.cy.ai.AiFacade;
import com.cy.ai.model.ChatCompletionChunk;
import com.cy.ai.model.ChatCompletionRequest;
import com.cy.ai.model.ChatCompletionResponse;
import com.cy.ai.model.ChatMessage;
import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.AttributeKey;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.context.LoopToolCall;
import com.cy.orrery.agent.runtime.context.RunCancellationException;
import com.cy.orrery.agent.runtime.event.RuntimeEvent;
import com.cy.orrery.agent.runtime.event.RuntimeEventType;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import com.cy.orrery.agent.runtime.llm.LlmCallOptions;
import com.cy.orrery.agent.runtime.llm.LlmInvoker;
import com.cy.orrery.agent.runtime.llm.impl.TruncationContinuationDecorator;
import com.cy.orrery.agent.runtime.loop.AgentStop;
import com.cy.orrery.agent.runtime.llm.LlmInvoker.LlmRoundResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiFacadeLlmInvoker 单元测试（流式聚合 / 同步路径 / 截断语义 / 失败重试语义 / 调用选项翻译）。
 *
 * @author pcy
 * @since 2026/08/27
 */
class AiFacadeLlmInvokerTest {

    /** 手写 AiFacade 假实现：脚本化返回流式 / 同步结果并记录最近一次请求与目标 provider。 */
    private static final class FakeAiFacade extends AiFacade {

        private Flux<ChatCompletionChunk> streamScript = Flux.empty();
        private ChatCompletionResponse syncResponse;
        private ChatCompletionRequest lastRequest;
        private String lastProvider;

        FakeAiFacade() {
            super(null);
        }

        @Override
        public Flux<ChatCompletionChunk> chatStream(ChatCompletionRequest request) {
            this.lastRequest = request;
            return streamScript;
        }

        @Override
        public Flux<ChatCompletionChunk> chatStream(String providerName, ChatCompletionRequest request) {
            this.lastProvider = providerName;
            this.lastRequest = request;
            return streamScript;
        }

        @Override
        public ChatCompletionResponse chatSync(String providerName, ChatCompletionRequest request) {
            this.lastProvider = providerName;
            this.lastRequest = request;
            return syncResponse;
        }

        @Override
        public ChatCompletionResponse chatSync(ChatCompletionRequest request) {
            this.lastRequest = request;
            return syncResponse;
        }
    }

    // ========================= chunk 构造辅助 =========================

    private static ChatCompletionChunk contentChunk(String delta) {
        return ChatCompletionChunk.builder()
                .choices(List.of(ChatCompletionChunk.Choice.builder()
                        .delta(ChatCompletionChunk.Delta.builder().content(delta).build())
                        .build()))
                .build();
    }

    private static ChatCompletionChunk reasoningChunk(String delta) {
        return ChatCompletionChunk.builder()
                .choices(List.of(ChatCompletionChunk.Choice.builder()
                        .delta(ChatCompletionChunk.Delta.builder().reasoningContent(delta).build())
                        .build()))
                .build();
    }

    private static ChatCompletionChunk toolCallChunk(Integer index, String id,
                                                     String name, String arguments) {
        return ChatCompletionChunk.builder()
                .choices(List.of(ChatCompletionChunk.Choice.builder()
                        .delta(ChatCompletionChunk.Delta.builder()
                                .toolCalls(List.of(ChatCompletionChunk.ToolCallChunk.builder()
                                        .index(index).id(id).type("function")
                                        .function(ChatMessage.FunctionCall.builder()
                                                .name(name).arguments(arguments).build())
                                        .build()))
                                .build())
                        .build()))
                .build();
    }

    private static ChatCompletionChunk finishChunk(String finishReason) {
        return ChatCompletionChunk.builder()
                .choices(List.of(ChatCompletionChunk.Choice.builder()
                        .finishReason(finishReason).build()))
                .build();
    }

    private static ChatCompletionChunk usageChunk(int promptTokens, int completionTokens) {
        return ChatCompletionChunk.builder()
                .usage(ChatCompletionResponse.Usage.builder()
                        .promptTokens(promptTokens).completionTokens(completionTokens)
                        .totalTokens(promptTokens + completionTokens).build())
                .build();
    }

    /**
     * 构造运行上下文（模拟引擎已分配步骤序号：STEP_STARTED 之后才调用 LLM）。
     *
     * @param bus 事件总线
     * @return 运行上下文（当前步骤序号 1）
     */
    private static AgentRunContext newContext(InMemoryRuntimeEventBus bus) {
        AgentRunContext ctx = new AgentRunContext("run-1", bus, null, List.of(
                LoopMessage.system("系统提示"),
                LoopMessage.user("你好"),
                LoopMessage.assistant("好的", "想了想",
                        List.of(new LoopToolCall("c1", "search", "{\"q\":\"x\"}"))),
                LoopMessage.tool("c1", "{\"r\":1}")));
        ctx.nextStepIndex();
        return ctx;
    }

    /**
     * 运行级模型目标：解析器返回 provider+model 时按名调用指定 provider 并覆盖请求模型。
     */
    @Test
    void shouldRouteToTargetProviderAndModel() {
        FakeAiFacade ai = new FakeAiFacade();
        ai.streamScript = Flux.just(contentChunk("正文"), finishChunk("stop"));
        AttributeKey<String> providerKey = new AttributeKey<>("test.llm.provider", String.class);
        AttributeKey<String> modelKey = new AttributeKey<>("test.llm.model", String.class);
        AiFacadeLlmInvoker invoker = new AiFacadeLlmInvoker(ai, List::of, null, true,
                ctx -> {
                    String provider = ctx.getAttr(providerKey);
                    String model = ctx.getAttr(modelKey);
                    return provider == null && model == null ? null : new LlmModelTarget(provider, model);
                }, null);

        AgentRunContext ctx = newContext(new InMemoryRuntimeEventBus("run-1"));
        ctx.putAttr(providerKey, "deepseek");
        ctx.putAttr(modelKey, "deepseek-v3");

        LlmRoundResult result = invoker.invoke(ctx, 0, List.of());

        assertFalse(result.failed());
        assertEquals("deepseek", ai.lastProvider);
        assertEquals("deepseek-v3", ai.lastRequest.getModel());
    }

    /**
     * 模型目标为空（解析器返回 null）时回退平台默认：走单参重载，不指定 provider。
     */
    @Test
    void shouldFallbackToDefaultWhenModelTargetEmpty() {
        FakeAiFacade ai = new FakeAiFacade();
        ai.streamScript = Flux.just(contentChunk("正文"), finishChunk("stop"));
        AiFacadeLlmInvoker invoker = new AiFacadeLlmInvoker(ai, List::of, null, true, ctx -> null, null);

        LlmRoundResult result = invoker.invoke(newContext(new InMemoryRuntimeEventBus("run-1")), 0, List.of());

        assertFalse(result.failed());
        assertNull(ai.lastProvider);
    }

    /**
     * 思考档位解析器：运行上下文属性求值后写入请求 thinkingLevel（wire 翻译归 provider）。
     */
    @Test
    void shouldApplyThinkingLevelFromResolver() {
        FakeAiFacade ai = new FakeAiFacade();
        ai.streamScript = Flux.just(contentChunk("正文"), finishChunk("stop"));
        AttributeKey<String> thinkingKey = new AttributeKey<>("test.thinking.level", String.class);
        AiFacadeLlmInvoker invoker = new AiFacadeLlmInvoker(ai, List::of, null, true, null,
                ctx -> ctx.getAttr(thinkingKey));

        AgentRunContext ctx = newContext(new InMemoryRuntimeEventBus("run-1"));
        ctx.putAttr(thinkingKey, "off");

        LlmRoundResult result = invoker.invoke(ctx, 0, List.of());

        assertFalse(result.failed());
        assertEquals("off", ai.lastRequest.getThinkingLevel());
    }

    /**
     * 思考档位解析器缺席 / 求值空白时请求不携带 thinkingLevel（跟随 provider 全局配置）。
     */
    @Test
    void shouldOmitThinkingLevelWhenResolverAbsent() {
        FakeAiFacade ai = new FakeAiFacade();
        ai.streamScript = Flux.just(contentChunk("正文"), finishChunk("stop"));
        AiFacadeLlmInvoker invoker = new AiFacadeLlmInvoker(ai, List::of, null, true, null, null);

        LlmRoundResult result = invoker.invoke(newContext(new InMemoryRuntimeEventBus("run-1")), 0, List.of());

        assertFalse(result.failed());
        assertNull(ai.lastRequest.getThinkingLevel());
    }

    /**
     * 流式聚合：content / reasoning 拼接、tool_calls 分片归并、usage 聚合、
     * TEXT_DELTA 事件按序下发、请求装配正确。
     */
    @Test
    void shouldAggregateStreamingChunksAndEmitDeltas() {
        FakeAiFacade ai = new FakeAiFacade();
        ai.streamScript = Flux.just(
                contentChunk("你好"), contentChunk("世界"),
                reasoningChunk("想"),
                toolCallChunk(0, "call-1", "sea", "{\"q\":"),
                toolCallChunk(0, null, null, "\"x\"}"),
                finishChunk("tool_calls"),
                usageChunk(10, 5));
        InMemoryRuntimeEventBus bus = new InMemoryRuntimeEventBus("run-1");
        List<RuntimeEvent> events = new ArrayList<>();
        bus.addListener(events::add);
        AgentRunContext ctx = newContext(bus);
        AiFacadeLlmInvoker invoker = new AiFacadeLlmInvoker(ai,
                () -> List.of(Map.of("type", "function", "function", Map.of("name", "search"))),
                null, true, null, null);

        LlmRoundResult result = invoker.invoke(ctx, 0, invoker.toolSpecs());

        assertFalse(result.failed());
        assertEquals("你好世界", result.content());
        assertEquals("想", result.reasoning());
        assertEquals(1, result.toolCalls().size());
        assertEquals(new LoopToolCall("call-1", "sea", "{\"q\":\"x\"}"), result.toolCalls().getFirst());
        assertEquals(10, result.promptTokens());
        assertEquals(5, result.completionTokens());

        // TEXT_DELTA 事件按序下发（stepIndex 取当前步骤游标）
        List<RuntimeEvent> deltas = events.stream()
                .filter(e -> e.type() == RuntimeEventType.TEXT_DELTA).toList();
        assertEquals(3, deltas.size());
        assertEquals(1, deltas.get(0).data().get("stepIndex"));
        assertEquals("你好", deltas.get(0).data().get("delta"));
        assertEquals("text", deltas.get(0).data().get("kind"));
        assertEquals("世界", deltas.get(1).data().get("delta"));
        assertEquals("想", deltas.get(2).data().get("delta"));
        assertEquals("reasoning", deltas.get(2).data().get("kind"));

        // 请求装配：streamOptions.includeUsage、tools、消息互转
        ChatCompletionRequest request = ai.lastRequest;
        assertEquals(Boolean.TRUE, request.getStreamOptions().getIncludeUsage());
        assertEquals(1, request.getTools().size());
        assertEquals(4, request.getMessages().size());
        assertEquals("system", request.getMessages().get(0).getRole());
        assertEquals("你好", request.getMessages().get(1).getContent());
        ChatMessage assistantMsg = request.getMessages().get(2);
        assertEquals("好的", assistantMsg.getContent());
        assertEquals("想了想", assistantMsg.getReasoningContent());
        assertEquals(1, assistantMsg.getToolCalls().size());
        assertEquals("c1", assistantMsg.getToolCalls().getFirst().getId());
        assertEquals("function", assistantMsg.getToolCalls().getFirst().getType());
        assertEquals("search", assistantMsg.getToolCalls().getFirst().getFunction().getName());
        assertEquals("{\"q\":\"x\"}", assistantMsg.getToolCalls().getFirst().getFunction().getArguments());
        assertEquals("c1", request.getMessages().get(3).getToolCallId());
        assertEquals("{\"r\":1}", request.getMessages().get(3).getContent());
    }

    /**
     * 同步路径：一次性聚合完整响应，不产生 TEXT_DELTA，不设置 streamOptions。
     */
    @Test
    void shouldAggregateSyncResponse() {
        FakeAiFacade ai = new FakeAiFacade();
        ai.syncResponse = ChatCompletionResponse.builder()
                .choices(List.of(ChatCompletionResponse.Choice.builder()
                        .message(ChatMessage.builder()
                                .role("assistant").content("同步答复")
                                .toolCalls(List.of(ChatMessage.ToolCall.builder()
                                        .id("c9").type("function")
                                        .function(ChatMessage.FunctionCall.builder()
                                                .name("search").arguments("{}").build())
                                        .build()))
                                .build())
                        .finishReason("tool_calls").build()))
                .usage(ChatCompletionResponse.Usage.builder()
                        .promptTokens(7).completionTokens(3).totalTokens(10).build())
                .build();
        InMemoryRuntimeEventBus bus = new InMemoryRuntimeEventBus("run-1");
        List<RuntimeEvent> events = new ArrayList<>();
        bus.addListener(events::add);
        AgentRunContext ctx = newContext(bus);
        AiFacadeLlmInvoker invoker = new AiFacadeLlmInvoker(ai, List::of, null, false, null, null);

        LlmRoundResult result = invoker.invoke(ctx, 0, List.of());

        assertFalse(result.failed());
        assertEquals("同步答复", result.content());
        assertEquals(List.of(new LoopToolCall("c9", "search", "{}")), result.toolCalls());
        assertEquals(7, result.promptTokens());
        assertEquals(3, result.completionTokens());
        assertNull(ai.lastRequest.getStreamOptions());
        assertTrue(events.isEmpty());
    }

    /**
     * 截断语义：finish_reason=length → 非 failed（errorCode=null）且 stopHint=OUTPUT_TRUNCATED，
     * 已聚合内容保留（供 TruncationContinuationDecorator 续接）。
     */
    @Test
    void shouldExpressTruncationAsStopHintNotFailure() {
        FakeAiFacade ai = new FakeAiFacade();
        ai.streamScript = Flux.just(
                contentChunk("半截正文"), finishChunk("length"), usageChunk(8, 4));
        AgentRunContext ctx = newContext(new InMemoryRuntimeEventBus("run-1"));
        AiFacadeLlmInvoker invoker = new AiFacadeLlmInvoker(ai, List::of, null, true, null, null);

        LlmRoundResult result = invoker.invoke(ctx, 0, List.of());

        assertFalse(result.failed());
        assertNull(result.errorCode());
        assertEquals(AgentStop.OUTPUT_TRUNCATED, result.stopHint());
        assertEquals("半截正文", result.content());
        assertEquals(8, result.promptTokens());
        assertEquals(4, result.completionTokens());
    }

    /**
     * 内容过滤语义：finish_reason=content_filter → CONTENT_FILTERED、不可重试。
     */
    @Test
    void shouldExpressContentFilterAsNonRetryableFailure() {
        FakeAiFacade ai = new FakeAiFacade();
        ai.streamScript = Flux.just(finishChunk("content_filter"));
        AgentRunContext ctx = newContext(new InMemoryRuntimeEventBus("run-1"));
        AiFacadeLlmInvoker invoker = new AiFacadeLlmInvoker(ai, List::of, null, true, null, null);

        LlmRoundResult result = invoker.invoke(ctx, 0, List.of());

        assertTrue(result.failed());
        assertEquals("CONTENT_FILTERED", result.errorCode());
        assertNull(result.stopHint());
        assertFalse(result.failedRetryable());
    }

    /**
     * 失败重试语义：未发射任何增量的流式失败 → stopHint=null 且可重试（传输级瞬时态）。
     */
    @Test
    void shouldMarkPreDeltaStreamFailureRetryable() {
        FakeAiFacade ai = new FakeAiFacade();
        ai.streamScript = Flux.error(new IllegalStateException("连接重置"));
        AgentRunContext ctx = newContext(new InMemoryRuntimeEventBus("run-1"));
        AiFacadeLlmInvoker invoker = new AiFacadeLlmInvoker(ai, List::of, null, true, null, null);

        LlmRoundResult result = invoker.invoke(ctx, 0, List.of());

        assertTrue(result.failed());
        assertEquals("LLM_CALL_FAILED", result.errorCode());
        assertEquals("连接重置", result.errorMessage());
        assertNull(result.stopHint());
        assertTrue(result.failedRetryable());
    }

    /**
     * 失败重试语义：已发射增量后的流式失败 → stopHint=LLM_ERROR 且不可重试
     * （重试会把已下发正文重复推送）。
     */
    @Test
    void shouldMarkPostDeltaStreamFailureNonRetryable() {
        FakeAiFacade ai = new FakeAiFacade();
        ai.streamScript = Flux.concat(
                Flux.just(contentChunk("已下发")),
                Flux.error(new IllegalStateException("断流")));
        AgentRunContext ctx = newContext(new InMemoryRuntimeEventBus("run-1"));
        AiFacadeLlmInvoker invoker = new AiFacadeLlmInvoker(ai, List::of, null, true, null, null);

        LlmRoundResult result = invoker.invoke(ctx, 0, List.of());

        assertTrue(result.failed());
        assertEquals("LLM_CALL_FAILED", result.errorCode());
        assertEquals(AgentStop.LLM_ERROR, result.stopHint());
        assertFalse(result.failedRetryable());
    }

    /**
     * 流式订阅取消传播：订阅期间逐 chunk 检查取消信号，命中即以
     * RunCancellationException 终止订阅（不等 blockLast 自然结束，
     * 已取消的运行不再接收模型输出），由运行器收敛 CANCELLED 终态。
     */
    @Test
    void shouldAbortStreamingSubscriptionWhenCancelled() {
        InMemoryRuntimeEventBus bus = new InMemoryRuntimeEventBus("run-1");
        List<RuntimeEvent> events = new ArrayList<>();
        bus.addListener(events::add);
        AgentRunContext ctx = newContext(bus);
        FakeAiFacade ai = new FakeAiFacade();
        // 首 chunk 到达即取消（模拟运行期取消信号），后续 chunk 不应再被聚合
        ai.streamScript = Flux.just(contentChunk("a"), contentChunk("b"), contentChunk("c"))
                .doOnNext(chunk -> bus.cancel("用户取消"));
        AiFacadeLlmInvoker invoker = new AiFacadeLlmInvoker(ai, List::of, null, true, null, null);

        RunCancellationException ex = assertThrows(RunCancellationException.class,
                () -> invoker.invoke(ctx, 0, List.of()));

        assertEquals("llm_stream_1", ex.phase());
        // 取消检查先于聚合：无 TEXT_DELTA 下发
        assertTrue(events.stream().noneMatch(e -> e.type() == RuntimeEventType.TEXT_DELTA));
    }

    /**
     * 同步失败一律可重试（不产生增量）。
     */
    @Test
    void shouldMarkSyncFailureRetryable() {
        FakeAiFacade ai = new FakeAiFacade();
        AiFacadeLlmInvoker invoker = new AiFacadeLlmInvoker(ai, List::of, null, false, null, null);
        AgentRunContext ctx = newContext(new InMemoryRuntimeEventBus("run-1"));

        // chatSync 返回 null（脚本未设置）→ 失败但可重试
        LlmRoundResult result = invoker.invoke(ctx, 0, List.of());

        assertTrue(result.failed());
        assertEquals("LLM_CALL_FAILED", result.errorCode());
        assertTrue(result.failedRetryable());
    }

    /**
     * 调用选项翻译：response_format / tool_choice 透传到请求；
     * 请求定制器最后执行（model / temperature / extra 扩展参数）。
     */
    @Test
    void shouldTranslateCallOptionsAndApplyCustomizer() {
        FakeAiFacade ai = new FakeAiFacade();
        ai.streamScript = Flux.just(contentChunk("x"), finishChunk("stop"));
        AgentRunContext ctx = newContext(new InMemoryRuntimeEventBus("run-1"));
        AiFacadeLlmInvoker invoker = new AiFacadeLlmInvoker(ai, List::of, request -> {
            request.setModel("qwen3");
            request.setTemperature(0.3);
            request.setMaxTokens(1024);
            request.extra("enable_thinking", true);
        }, true, null, null);

        invoker.invoke(ctx, 0, List.of(), LlmCallOptions.jsonObject());

        Map<?, ?> responseFormat = assertInstanceOf(Map.class, ai.lastRequest.getResponseFormat());
        assertEquals("json_object", responseFormat.get("type"));
        assertEquals("qwen3", ai.lastRequest.getModel());
        assertEquals(Double.valueOf(0.3), ai.lastRequest.getTemperature());
        assertEquals(Integer.valueOf(1024), ai.lastRequest.getMaxTokens());
        assertEquals(Boolean.TRUE, ai.lastRequest.getExtraParams().get("enable_thinking"));

        invoker.invoke(ctx, 0, List.of(), LlmCallOptions.of(null, "none"));
        assertEquals("none", ai.lastRequest.getToolChoice());
    }
}
