// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.bridge.cyai;


import com.cy.ai.AiFacade;
import com.cy.ai.model.ChatCompletionChunk;
import com.cy.ai.model.ChatCompletionRequest;
import com.cy.ai.model.ChatCompletionResponse;
import com.cy.ai.model.ChatMessage;
import com.cy.ai.model.ContentPart;
import com.cy.ai.model.StreamOptions;
import com.cy.orrery.agent.runtime.constant.RuntimeErrorCode;
import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.context.LoopToolCall;
import com.cy.orrery.agent.runtime.context.RunCancellationException;
import com.cy.orrery.agent.runtime.event.Events;
import com.cy.orrery.agent.runtime.llm.LlmCallOptions;
import com.cy.orrery.agent.runtime.llm.LlmInvoker;
import com.cy.orrery.agent.runtime.llm.impl.TruncationContinuationDecorator;
import com.cy.orrery.agent.runtime.loop.AgentStop;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

/**
 * cy-common-ai 生产 LLM 适配器：把 {@link LlmInvoker} SPI 对接到 {@link AiFacade}。
 * <p>
 * 职责边界：
 * <ul>
 *     <li><b>消息互转</b>：{@link LoopMessage} ↔ {@link ChatMessage}（assistant 的
 *         tool_calls 与 tool 角色回执按 toolCallId 配对，reasoningContent 双向透传）；</li>
 *     <li><b>流式语义</b>：streaming=true 时经 {@code chatStream} 逐 chunk 聚合，
 *         正文 / 思考增量即时经 {@link Events#textDelta} 下发（kind=text / reasoning，
 *         stepIndex 取 {@code ctx.currentStepIndex()}——引擎在 STEP_STARTED 之后才调用本适配器，
 *         游标即当前步骤），tool_calls 分片按 index 聚合拼接（id 首 chunk 到达，
 *         name / arguments 分片追加）；</li>
 *     <li><b>截断语义</b>：finish_reason=length 时返回 errorCode=null（即 failed()=false）
 *         且 stopHint={@link AgentStop#OUTPUT_TRUNCATED} 的结果，已聚合的 content /
 *         reasoning / toolCalls 原样保留——{@link TruncationContinuationDecorator}
 *         凭 stopHint 识别并自动续接，未装配续接装饰器时由引擎按截断语义终止；</li>
 *     <li><b>失败重试语义</b>：流式尚未发射任何正文增量时的失败按传输级瞬时态处理
 *         （stopHint=null、failedRetryable=true，可安全重试——下游尚无可见输出）；
 *         已发射增量后的失败置 stopHint={@link AgentStop#LLM_ERROR}、failedRetryable=false
 *         （重试会把已下发正文重复推送，可重试标志随 LoopOutcome 透传给宿主裁决整轮重跑）；
 *         非流式路径不产生增量，失败一律按可重试处理。</li>
 * </ul>
 * </p>
 * <p>
 * 请求装配：messages / tools / streamOptions（include_usage=true，仅流式）由本类填充；
 * {@link LlmCallOptions} 翻译为 response_format / tool_choice 请求字段；
 * {@code requestCustomizer} 最后执行，由宿主填充 model / temperature / maxTokens /
 * 厂商扩展参数（{@code extra}），可覆盖内置值。
 * </p>
 *
 * @author pcy
 * @since 2026/08/27
 */
public class AiFacadeLlmInvoker implements LlmInvoker {

    private static final Logger log = LoggerFactory.getLogger(AiFacadeLlmInvoker.class);

    /** finish_reason：输出达到 max_tokens 上限被截断。 */
    private static final String FINISH_LENGTH = "length";

    /** finish_reason：输出被内容安全过滤拦截。 */
    private static final String FINISH_CONTENT_FILTER = "content_filter";

    private final AiFacade aiFacade;
    private final Supplier<List<Map<String, Object>>> toolSpecsSupplier;
    private final Consumer<ChatCompletionRequest> requestCustomizer;
    private final boolean streaming;
    private final Function<AgentRunContext, LlmModelTarget> modelTargetResolver;
    private final Function<AgentRunContext, String> thinkingLevelResolver;

    /**
     * 构造适配器（唯一权威构造器，全部旋钮显式声明）。
     *
     * @param aiFacade              AI 门面
     * @param toolSpecsSupplier     工具规格供给器（每次 {@link #toolSpecs()} 调用时取新值，
     *                              支持宿主按运行期状态动态增减工具；为 null 视为无工具）
     * @param requestCustomizer     请求定制器（最后执行，可覆盖内置值与解析器模型；为 null 跳过）
     * @param streaming             true 走流式（逐 chunk 聚合 + TEXT_DELTA 下发），false 走同步
     * @param modelTargetResolver   运行级模型目标解析器（每轮调用以运行上下文求值一次：
     *                              返回 null 或空目标表示跟随平台默认 provider / 默认模型；
     *                              为 null 禁用此能力）
     * @param thinkingLevelResolver 运行级思考档位解析器（每轮调用以运行上下文求值一次：
     *                              返回值写入 {@code ChatCompletionRequest.thinkingLevel}，
     *                              由 provider 按厂商协议翻译；null / 空白 / auto 表示
     *                              跟随 provider 全局配置；为 null 禁用此能力）
     */
    public AiFacadeLlmInvoker(AiFacade aiFacade,
                              Supplier<List<Map<String, Object>>> toolSpecsSupplier,
                              Consumer<ChatCompletionRequest> requestCustomizer,
                              boolean streaming,
                              Function<AgentRunContext, LlmModelTarget> modelTargetResolver,
                              Function<AgentRunContext, String> thinkingLevelResolver) {
        this.aiFacade = aiFacade;
        this.toolSpecsSupplier = toolSpecsSupplier;
        this.requestCustomizer = requestCustomizer;
        this.streaming = streaming;
        this.modelTargetResolver = modelTargetResolver;
        this.thinkingLevelResolver = thinkingLevelResolver;
    }

    @Override
    public List<Map<String, Object>> toolSpecs() {
        List<Map<String, Object>> specs = toolSpecsSupplier == null ? null : toolSpecsSupplier.get();
        return specs == null ? List.of() : specs;
    }

    @Override
    public LlmRoundResult invoke(AgentRunContext ctx, int roundIndex, List<Map<String, Object>> toolSpecs) {
        return doInvoke(ctx, toolSpecs, null);
    }

    @Override
    public LlmRoundResult invoke(AgentRunContext ctx, int roundIndex, List<Map<String, Object>> toolSpecs,
                                 LlmCallOptions options) {
        return doInvoke(ctx, toolSpecs, options);
    }

    /**
     * 执行一轮调用（组装请求后按流式 / 同步分流）。
     *
     * @param ctx       运行上下文
     * @param toolSpecs 工具规格
     * @param options   调用选项（可空）
     * @return 归一化轮结果
     */
    private LlmRoundResult doInvoke(AgentRunContext ctx, List<Map<String, Object>> toolSpecs,
                                    LlmCallOptions options) {
        // 模型目标每轮解析一次（空目标 = 跟随平台默认 provider / 默认模型）
        LlmModelTarget modelTarget = resolveModelTarget(ctx);
        ChatCompletionRequest request;
        try {
            request = buildRequest(ctx, toolSpecs, options, modelTarget);
        } catch (Exception e) {
            // 请求组装失败是确定性结局（消息形态 / 定制器缺陷），重试无意义
            log.warn("LLM 请求组装失败 runId={} error={}", ctx.getRunId(), e.toString());
            return LlmRoundResult.fail(RuntimeErrorCode.LLM_CALL_FAILED.code(), errorMessage(e), null, false);
        }
        return streaming ? invokeStreaming(ctx, request, options, modelTarget)
                : invokeSync(ctx, request, options, modelTarget);
    }

    /**
     * 解析运行级模型目标（解析器未装配或求值异常时回退空目标——不影响主链路）。
     *
     * @param ctx 运行上下文
     * @return 模型目标；空目标或 null 表示跟随平台默认
     */
    private LlmModelTarget resolveModelTarget(AgentRunContext ctx) {
        if (modelTargetResolver == null) {
            return null;
        }
        try {
            LlmModelTarget target = modelTargetResolver.apply(ctx);
            return target == null || target.isEmpty() ? null : target;
        } catch (Exception e) {
            log.warn("模型目标解析失败，回退平台默认: runId={} error={}", ctx.getRunId(), e.toString());
            return null;
        }
    }

    /**
     * 解析运行级思考档位（解析器未装配或求值异常时回退 null——跟随 provider 全局配置）。
     *
     * @param ctx 运行上下文
     * @return 思考档位；null 表示跟随 provider 全局配置
     */
    private String resolveThinkingLevel(AgentRunContext ctx) {
        if (thinkingLevelResolver == null) {
            return null;
        }
        try {
            String level = thinkingLevelResolver.apply(ctx);
            return level == null || level.isBlank() ? null : level;
        } catch (Exception e) {
            log.warn("思考档位解析失败，回退 provider 全局配置: runId={} error={}", ctx.getRunId(), e.toString());
            return null;
        }
    }

    /**
     * 组装厂商请求（消息互转 + 工具规格 + 流式用量 + 调用选项翻译 + 宿主定制）。
     *
     * @param ctx       运行上下文
     * @param toolSpecs 工具规格
     * @param options   调用选项（可空）
     * @return 厂商请求
     */
    private ChatCompletionRequest buildRequest(AgentRunContext ctx, List<Map<String, Object>> toolSpecs,
                                               LlmCallOptions options, LlmModelTarget modelTarget) {
        List<ChatMessage> messages = new ArrayList<>(ctx.getMessages().size());
        for (LoopMessage message : ctx.getMessages()) {
            messages.add(toChatMessage(message));
        }
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(messages)
                .tools(toolSpecs == null || toolSpecs.isEmpty()
                        ? null : new ArrayList<Object>(toolSpecs))
                .build();
        if (streaming) {
            // 流式末块携带 token 用量（厂商支持时）
            request.setStreamOptions(StreamOptions.builder().includeUsage(true).build());
        }
        if (options != null) {
            if (options.responseFormat() != null) {
                request.setResponseFormat(options.responseFormat());
            }
            if (options.toolChoice() != null) {
                request.setToolChoice(options.toolChoice());
            }
        }
        // 运行级模型覆盖在宿主定制之前：requestCustomizer 仍可最终改写（显式定制优先）
        if (modelTarget != null && modelTarget.model() != null && !modelTarget.model().isBlank()) {
            request.setModel(modelTarget.model());
        }
        // 运行级思考档位：写入厂商无关字段，wire 翻译由 provider 按厂商协议完成
        String thinkingLevel = resolveThinkingLevel(ctx);
        if (thinkingLevel != null) {
            request.setThinkingLevel(thinkingLevel);
        }
        // 宿主定制最后执行：model / temperature / maxTokens / 厂商扩展参数由其兜底填充，可覆盖内置值
        if (requestCustomizer != null) {
            requestCustomizer.accept(request);
        }
        return request;
    }

    /**
     * 流式调用：逐 chunk 聚合，正文 / 思考增量即时经事件总线下发。
     * <p>
     * 订阅期间逐 chunk 检查取消信号（doOnNext）：已取消立即以
     * {@link RunCancellationException} 终止订阅，由运行器收敛 CANCELLED 终态——
     * 不等 blockLast 自然结束，避免取消后仍全额接收模型输出。
     * </p>
     *
     * @param ctx     运行上下文
     * @param request 厂商请求
     * @return 归一化轮结果
     */
    private LlmRoundResult invokeStreaming(AgentRunContext ctx, ChatCompletionRequest request,
                                           LlmCallOptions options, LlmModelTarget modelTarget) {
        int stepIndex = ctx.currentStepIndex();
        long startMs = System.currentTimeMillis();
        StreamAccumulator acc = new StreamAccumulator(options);
        // 先落请求的覆盖模型，厂商回传后升级为实际值——失败遥测也能分辨覆盖是否走到调用面
        acc.model = requestedModel(modelTarget);
        try {
            Flux<ChatCompletionChunk> stream = modelTarget != null && modelTarget.provider() != null
                    && !modelTarget.provider().isBlank()
                    ? aiFacade.chatStream(modelTarget.provider(), request)
                    : aiFacade.chatStream(request);
            stream
                    .doOnNext(chunk -> {
                        if (ctx.isCancelled()) {
                            throw new RunCancellationException(ctx.getRunId(),
                                    "llm_stream_" + stepIndex);
                        }
                        accumulateChunk(ctx, stepIndex, chunk, acc);
                    })
                    .blockLast();
        } catch (RunCancellationException e) {
            // 取消向上传播（区别于失败：取消由运行器收敛 CANCELLED，不进重试）
            throw e;
        } catch (Exception e) {
            // Reactor 可能把 doOnNext 抛出的取消异常包装为复合异常：解包后仍按取消传播
            RunCancellationException cancellation = findCancellation(e);
            if (cancellation != null) {
                throw cancellation;
            }
            // 流式失败的重试分界：已发射增量后重试会把已下发正文重复推送，
            // 故置 failedRetryable=false 并按 LLM_ERROR 语义终止；
            // 未发射任何增量的失败按传输级瞬时态处理（stopHint=null，可重试；
            // 4xx 确定性失败不参与重试——重试空耗配额）
            log.warn("LLM 流式调用失败 runId={} step={} emittedDelta={} error={}",
                    ctx.getRunId(), stepIndex, acc.emittedAnyDelta, e.toString());
            emitTelemetry(ctx, options, acc, startMs, null);
            boolean retryable = !acc.emittedAnyDelta && !isDeterministicClientError(e);
            if (acc.emittedAnyDelta) {
                return LlmRoundResult.fail(RuntimeErrorCode.LLM_CALL_FAILED.code(),
                        errorMessage(e), AgentStop.LLM_ERROR, false);
            }
            return LlmRoundResult.fail(RuntimeErrorCode.LLM_CALL_FAILED.code(),
                    errorMessage(e), null, retryable);
        }
        LlmRoundResult result = acc.toResult();
        emitTelemetry(ctx, options, acc, startMs, acc.finishReason);
        return result;
    }

    /**
     * 请求的覆盖模型名（空白归 null；遥测失败路径与厂商未回传时的兜底）。
     *
     * @param modelTarget 运行级模型目标（可空）
     * @return 覆盖模型名
     */
    private static String requestedModel(LlmModelTarget modelTarget) {
        return modelTarget != null && modelTarget.model() != null && !modelTarget.model().isBlank()
                ? modelTarget.model() : null;
    }

    /**
     * 发射 LLM 遥测事件（options.callId 非空时；同步 / 流式 / 失败路径统一）。
     *
     * @param ctx          运行上下文
     * @param options      调用选项（callId / 遥测开关）
     * @param acc          流式聚合器（token / model 来源）；同步路径为 null
     * @param startMs      调用开始时间
     * @param finishReason 生成结束原因（可空）
     */
    private void emitTelemetry(AgentRunContext ctx, LlmCallOptions options, StreamAccumulator acc,
                               long startMs, String finishReason) {
        if (options == null || options.callId() == null) {
            return;
        }
        int promptTokens = acc == null || acc.usage == null ? 0 : promptTokens(acc.usage);
        int completionTokens = acc == null || acc.usage == null ? 0 : completionTokens(acc.usage);
        Events.llmCall(ctx.getBus(), options.callId(), acc == null ? null : acc.model,
                promptTokens, completionTokens, System.currentTimeMillis() - startMs, finishReason);
    }

    /**
     * 同步调用：一次性聚合完整响应（不产生 TEXT_DELTA 事件）。
     *
     * @param ctx     运行上下文
     * @param request 厂商请求
     * @return 归一化轮结果
     */
    private LlmRoundResult invokeSync(AgentRunContext ctx, ChatCompletionRequest request,
                                      LlmCallOptions options, LlmModelTarget modelTarget) {
        long startMs = System.currentTimeMillis();
        ChatCompletionResponse response;
        try {
            response = modelTarget != null && modelTarget.provider() != null
                    && !modelTarget.provider().isBlank()
                    ? aiFacade.chatSync(modelTarget.provider(), request)
                    : aiFacade.chatSync(request);
        } catch (Exception e) {
            // 非流式路径不产生增量：传输级瞬时态可重试；4xx 确定性失败不重试（重试空耗配额）
            log.warn("LLM 同步调用失败 runId={} error={}", ctx.getRunId(), e.toString());
            emitSyncTelemetry(ctx, options, null, startMs, null, requestedModel(modelTarget));
            return LlmRoundResult.fail(RuntimeErrorCode.LLM_CALL_FAILED.code(),
                    errorMessage(e), null, !isDeterministicClientError(e));
        }
        if (response == null) {
            emitSyncTelemetry(ctx, options, null, startMs, null, requestedModel(modelTarget));
            return LlmRoundResult.fail(RuntimeErrorCode.LLM_CALL_FAILED.code(),
                    "模型无响应（response 为 null）", null, true);
        }
        ChatMessage message = response.getFirstMessage();
        List<LoopToolCall> calls = List.of();
        if (message != null && message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            calls = message.getToolCalls().stream()
                    .map(AiFacadeLlmInvoker::toLoopToolCall)
                    .toList();
        }
        ChatCompletionResponse.Usage usage = response.getUsage();
        String reportedModel = response.getModel();
        emitSyncTelemetry(ctx, options, usage, startMs, response.getFirstFinishReason(),
                reportedModel != null && !reportedModel.isBlank() ? reportedModel : requestedModel(modelTarget));
        return toRoundResult(
                message == null ? null : emptyToNull(message.getContent()),
                message == null ? null : emptyToNull(message.getReasoningContent()),
                calls, promptTokens(usage), completionTokens(usage), response.getFirstFinishReason());
    }

    /**
     * 同步路径遥测（options.callId 非空时）。
     *
     * @param ctx          运行上下文
     * @param options      调用选项
     * @param usage        用量（失败为 null）
     * @param startMs      开始时间
     * @param finishReason 结束原因（可空）
     * @param model        厂商回传的实际模型名（可空）
     */
    private void emitSyncTelemetry(AgentRunContext ctx, LlmCallOptions options,
                                   ChatCompletionResponse.Usage usage, long startMs, String finishReason,
                                   String model) {
        if (options == null || options.callId() == null) {
            return;
        }
        Events.llmCall(ctx.getBus(), options.callId(), model,
                promptTokens(usage), completionTokens(usage),
                System.currentTimeMillis() - startMs, finishReason);
    }

    /**
     * 聚合单个流式 chunk：正文 / 思考增量下发事件，tool_calls 分片按 index 归并。
     *
     * @param ctx       运行上下文
     * @param stepIndex 当前步骤序号
     * @param chunk     流式响应块
     * @param acc       流式聚合器
     */
    private void accumulateChunk(AgentRunContext ctx, int stepIndex, ChatCompletionChunk chunk,
                                 StreamAccumulator acc) {
        String contentDelta = chunk.getContent();
        if (contentDelta != null && !contentDelta.isEmpty()) {
            acc.content.append(contentDelta);
            // 静默 content（结构化 JSON 路径）：增量累积但不作为用户可见块下发，
            // 也不置 emittedAnyDelta（不影响流式失败的重试判定）——
            // content 是待解析的 JSON 不是给用户的正文，下发会刷屏。
            if (acc.options == null || !acc.options.suppressContentEmission()) {
                acc.emittedAnyDelta = true;
                Events.textDelta(ctx.getBus(), stepIndex, contentDelta, "text",
                        acc.options == null ? null : acc.options.label());
            }
        }
        String reasoningDelta = chunk.getReasoningContent();
        if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
            acc.reasoning.append(reasoningDelta);
            acc.emittedAnyDelta = true;
            Events.textDelta(ctx.getBus(), stepIndex, reasoningDelta, "reasoning",
                    acc.options == null ? null : acc.options.label());
        }
        if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
            ChatCompletionChunk.Delta delta = chunk.getChoices().getFirst().getDelta();
            if (delta != null && delta.getToolCalls() != null) {
                for (ChatCompletionChunk.ToolCallChunk toolCall : delta.getToolCalls()) {
                    acc.accumulateToolCall(toolCall);
                }
            }
        }
        if (chunk.getUsage() != null) {
            acc.usage = chunk.getUsage();
        }
        // 厂商回传的实际模型名（遥测展示用，反映运行级模型覆盖的真实生效情况）
        if (chunk.getModel() != null && !chunk.getModel().isBlank()) {
            acc.model = chunk.getModel();
        }
        String finishReason = chunk.getFinishReason();
        if (finishReason != null) {
            acc.finishReason = finishReason;
        }
    }

    /**
     * 按 finishReason 折叠归一化结果（截断 / 内容过滤 / 正常三类语义）。
     *
     * @param content          正文（可空）
     * @param reasoning        深度思考内容（可空）
     * @param toolCalls        工具调用列表
     * @param promptTokens     prompt token
     * @param completionTokens completion token
     * @param finishReason     生成结束原因（可空）
     * @return 归一化轮结果
     */
    private static LlmRoundResult toRoundResult(String content, String reasoning,
                                                List<LoopToolCall> toolCalls,
                                                int promptTokens, int completionTokens,
                                                String finishReason) {
        if (FINISH_LENGTH.equals(finishReason)) {
            // 截断不是失败：errorCode=null（failed()=false）但 stopHint=OUTPUT_TRUNCATED，
            // 已聚合内容原样保留——TruncationContinuationDecorator 凭 stopHint 自动续接，
            // 未装配续接装饰器时由引擎按截断语义终止
            return new LlmRoundResult(content, reasoning, toolCalls, promptTokens, completionTokens,
                    null, null, AgentStop.OUTPUT_TRUNCATED, true);
        }
        if (FINISH_CONTENT_FILTER.equals(finishReason)) {
            // 内容过滤是确定性结局：重试无意义（failedRetryable=false），
            // stopHint=null 由引擎按 LLM_ERROR 语义终止
            RuntimeErrorCode code = RuntimeErrorCode.CONTENT_FILTERED;
            return LlmRoundResult.fail(code.code(), code.format(), null, false);
        }
        return LlmRoundResult.ok(content, reasoning, toolCalls, promptTokens, completionTokens);
    }

    /**
     * LoopMessage → ChatMessage 转换（assistant tool_calls 与 tool 回执配对透传；
     * user 消息携带的图片地址翻译为 OpenAI content parts）。
     *
     * @param message 运行时消息
     * @return 厂商消息
     */
    private static ChatMessage toChatMessage(LoopMessage message) {
        ChatMessage.ChatMessageBuilder builder = ChatMessage.builder()
                .role(message.role())
                .content(message.content())
                .reasoningContent(message.reasoningContent())
                .toolCallId(message.toolCallId());
        if (message.imageUrls() != null && !message.imageUrls().isEmpty()) {
            List<ContentPart> parts = new ArrayList<>(message.imageUrls().size() + 1);
            if (message.content() != null && !message.content().isBlank()) {
                parts.add(ContentPart.ofText(message.content()));
            }
            for (String url : message.imageUrls()) {
                if (url != null && !url.isBlank()) {
                    parts.add(ContentPart.ofImageUrl(url));
                }
            }
            builder.contentParts(parts);
        }
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            List<ChatMessage.ToolCall> toolCalls = new ArrayList<>(message.toolCalls().size());
            for (LoopToolCall call : message.toolCalls()) {
                toolCalls.add(ChatMessage.ToolCall.builder()
                        .id(call.id())
                        .type("function")
                        .function(ChatMessage.FunctionCall.builder()
                                .name(call.name())
                                .arguments(call.arguments())
                                .build())
                        .build());
            }
            builder.toolCalls(toolCalls);
        }
        return builder.build();
    }

    /**
     * 厂商 ToolCall → LoopToolCall 转换（同步路径）。
     *
     * @param toolCall 厂商工具调用
     * @return 运行时工具调用
     */
    private static LoopToolCall toLoopToolCall(ChatMessage.ToolCall toolCall) {
        ChatMessage.FunctionCall function = toolCall.getFunction();
        return new LoopToolCall(toolCall.getId(),
                function == null ? null : function.getName(),
                function == null ? null : function.getArguments());
    }

    /**
     * 空串归一为 null（归一化结果的可空字段约定）。
     *
     * @param text 原始文本
     * @return 非空文本或 null
     */
    private static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    /**
     * 取 prompt token（null 归 0）。
     *
     * @param usage 用量（可空）
     * @return prompt token
     */
    private static int promptTokens(ChatCompletionResponse.Usage usage) {
        return usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
    }

    /**
     * 取 completion token（null 归 0）。
     *
     * @param usage 用量（可空）
     * @return completion token
     */
    private static int completionTokens(ChatCompletionResponse.Usage usage) {
        return usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
    }

    /**
     * 提取异常消息（空消息回退 toString，避免结果里出现 null 说明）。
     *
     * @param e 异常
     * @return 错误消息
     */
    private static String errorMessage(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.toString() : message;
    }

    /**
     * 从异常链（含 Reactor 复合异常的多个 cause）中定位取消异常。
     *
     * @param failure 流式调用失败
     * @return 取消异常；链中无取消为 null
     */
    private static RunCancellationException findCancellation(Throwable failure) {
        Deque<Throwable> queue = new ArrayDeque<>();
        queue.add(failure);
        Set<Throwable> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            Throwable t = queue.poll();
            if (t == null || !seen.add(t)) {
                continue;
            }
            if (t instanceof RunCancellationException cancellation) {
                return cancellation;
            }
            // Reactor 复合异常按 Iterable<Throwable> 展开（不直接引用其类型——
            // 不同版本可见性不一致；非复合异常的 instanceof 恒 false，零副作用）
            if (t instanceof Iterable<?> causes) {
                for (Object cause : causes) {
                    if (cause instanceof Throwable throwable) {
                        queue.offer(throwable);
                    }
                }
            }
            if (t.getCause() != null) {
                queue.offer(t.getCause());
            }
        }
        return null;
    }

    /**
     * 是否确定性客户端失败（HTTP 4xx）：重试只会得到同样结果，不参与退避重试。
     *
     * @param failure 调用失败
     * @return true = 4xx 客户端错误
     */
    private static boolean isDeterministicClientError(Throwable failure) {
        Throwable t = failure;
        while (t != null) {
            if (t instanceof WebClientResponseException wce && wce.getStatusCode().is4xxClientError()) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * 流式聚合器：把逐 chunk 增量拼成归一化轮结果。
     * <p>
     * 线程约定：只在 Flux 的 onNext 信号内写（响应式流规范保证 onNext 串行）、
     * 在 blockLast 终止返回后读（终止信号与阻塞唤醒建立 happens-before），故无需额外同步。
     * </p>
     */
    private static final class StreamAccumulator {

        private final LlmCallOptions options;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final TreeMap<Integer, ToolCallAccumulator> toolCalls = new TreeMap<>();
        private ChatCompletionResponse.Usage usage;
        private String finishReason;
        /** 模型名（先落请求覆盖模型，厂商回传后升级为实际值；遥测展示用） */
        private String model;
        private boolean emittedAnyDelta;

        private StreamAccumulator(LlmCallOptions options) {
            this.options = options;
        }

        /**
         * 归并一个工具调用分片（id 首 chunk 到达，name / arguments 分片追加）。
         *
         * @param chunk 工具调用分片
         */
        void accumulateToolCall(ChatCompletionChunk.ToolCallChunk chunk) {
            int index = chunk.getIndex() == null ? 0 : chunk.getIndex();
            ToolCallAccumulator acc = toolCalls.computeIfAbsent(index, key -> new ToolCallAccumulator());
            if (chunk.getId() != null) {
                acc.id = chunk.getId();
            }
            ChatMessage.FunctionCall function = chunk.getFunction();
            if (function != null) {
                if (function.getName() != null) {
                    acc.name.append(function.getName());
                }
                if (function.getArguments() != null) {
                    acc.arguments.append(function.getArguments());
                }
            }
        }

        /**
         * 折叠为归一化轮结果。
         *
         * @return 轮结果
         */
        LlmRoundResult toResult() {
            List<LoopToolCall> calls = toolCalls.values().stream()
                    .map(acc -> new LoopToolCall(acc.id, acc.name.toString(), acc.arguments.toString()))
                    .toList();
            return toRoundResult(
                    content.isEmpty() ? null : content.toString(),
                    reasoning.isEmpty() ? null : reasoning.toString(),
                    calls, promptTokens(usage), completionTokens(usage), finishReason);
        }
    }

    /**
     * 单个工具调用的分片聚合器。
     */
    private static final class ToolCallAccumulator {

        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
