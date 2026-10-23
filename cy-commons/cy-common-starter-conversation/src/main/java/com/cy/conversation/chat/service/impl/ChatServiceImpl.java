package com.cy.conversation.chat.service.impl;

import com.cy.ai.AiFacade;
import com.cy.ai.model.ChatCompletionChunk;
import com.cy.ai.model.ChatCompletionRequest;
import com.cy.ai.model.ChatMessage;
import com.cy.conversation.chat.service.ChatService;
import com.cy.conversation.chat.service.StreamSessionManager;
import com.cy.conversation.chat.support.ChatStreamErrorSupport;
import com.cy.conversation.chat.vo.ChatRequest;
import com.cy.conversation.chat.vo.ChatStreamResponse;
import com.cy.conversation.message.service.ChatMessageService;
import com.cy.conversation.metrics.ConversationMetrics;
import com.cy.conversation.session.entity.ConversationSession;
import com.cy.conversation.session.service.SessionService;
import com.cy.core.context.InvocationContextAccessor;
import com.cy.core.exception.CYException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天服务实现类
 * <p>
 * 实现与大模型的流式对话功能，包括：
 * - 会话自动创建
 * - 历史消息上下文构建
 * - 流式输出处理
 * - 消息持久化
 * - 智能命名触发
 * - 指标采集
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final String STREAM_URI = "/conversation/chat/stream";

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    /**
     * 默认历史消息数量限制
     */
    private static final int DEFAULT_HISTORY_LIMIT = 10;

    /** AI 统一门面，提供流式/非流式对话能力 */
    private final AiFacade aiFacade;
    /** 会话服务，用于创建/查询会话 */
    private final SessionService sessionService;
    /** 消息服务，用于持久化用户和助手消息 */
    private final ChatMessageService chatMessageService;
    /** 指标采集器，记录对话性能数据（可为 null） */
    private final ConversationMetrics metrics;
    /** 流式会话管理器，支持断线重连 */
    private final StreamSessionManager streamSessionManager;
    private final ChatStreamErrorSupport chatStreamErrorSupport;

    /**
     * 构造函数
     *
     * @param aiFacade              AI 统一门面
     * @param sessionService        会话服务
     * @param chatMessageService    消息服务
     * @param metrics               指标采集器（可为null）
     * @param streamSessionManager  流式会话管理器
     */
    public ChatServiceImpl(AiFacade aiFacade, SessionService sessionService,
                           ChatMessageService chatMessageService,
                           ConversationMetrics metrics,
                           StreamSessionManager streamSessionManager,
                           ChatStreamErrorSupport chatStreamErrorSupport) {
        this.aiFacade = aiFacade;
        this.sessionService = sessionService;
        this.chatMessageService = chatMessageService;
        this.metrics = metrics;
        this.streamSessionManager = streamSessionManager;
        this.chatStreamErrorSupport = chatStreamErrorSupport;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 流程概述：
     * <ol>
     *   <li>验证请求参数（用户登录状态、消息非空）</li>
     *   <li>准备会话（获取或创建）并保存用户消息</li>
     *   <li>构建历史上下文并发起流式请求</li>
     *   <li>逞序处理每个响应块，累积完整响应</li>
     *   <li>完成后异步保存 AI 回复、触发智能命名、记录指标</li>
     * </ol>
     * 通过 replay sink 支持客户端断线重连。
     * </p>
     */
    @Override
    public Flux<ChatStreamResponse> streamChat(ChatRequest request) {
        // 验证请求参数
        validateRequest(request);

        // 初始化上下文状态
        ChatContext ctx = new ChatContext(request);

        // 检测是否可重连到已有活跃流
        Long existingSessionId = ctx.sessionIdRef.get();
        if (existingSessionId != null && streamSessionManager.isActive(existingSessionId)) {
            log.info("重连到活跃的流式会话: {}", existingSessionId);
            return streamSessionManager.subscribe(existingSessionId);
        }

        // 创建 replay sink —— 所有已发射的块会缓存，供断线重连的客户端 replay
        Sinks.Many<ChatStreamResponse> sink = Sinks.many().replay().all();

        // 构建上游处理管线并独立订阅（不与 SSE 连接绑定）
        prepareSession(ctx)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(session -> {
                    // 会话创建后将本地 sink 注册到管理器，供重连使用
                    Long sessionId = ctx.sessionIdRef.get();
                    if (sessionId != null) {
                        streamSessionManager.register(sessionId, sink);
                    }
                    return executeStreamChat(ctx, session);
                })
                .flatMap(chunk -> Mono.justOrEmpty(processChunk(ctx, chunk)))
                .filter(response -> StringUtils.isNotEmpty(response.getContent())
                        || StringUtils.isNotEmpty(response.getReasoningContent()))
                .concatWith(Mono.defer(() -> completeChat(ctx)))
                .onErrorResume(e -> handleError(e, ctx))
                .doFinally(signal -> {
                    Long sessionId = ctx.sessionIdRef.get();
                    if (sessionId != null) {
                        streamSessionManager.remove(sessionId);
                    }
                    sink.tryEmitComplete();
                })
                .subscribe(
                        response -> sink.tryEmitNext(response),
                        error -> {
                            sink.tryEmitNext(chatStreamErrorSupport.toStreamError(error, STREAM_URI));
                            sink.tryEmitComplete();
                        }
                );

        // 返回 sink 的 Flux —— 客户端订阅此 Flux
        return sink.asFlux();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isStreamActive(Long sessionId) {
        return streamSessionManager.isActive(sessionId);
    }

    /** {@inheritDoc} */
    @Override
    public Flux<ChatStreamResponse> resumeStream(Long sessionId) {
        if (!streamSessionManager.isActive(sessionId)) {
            return Flux.empty();
        }
        log.info("客户端重连到活跃流，会话ID: {}", sessionId);
        return streamSessionManager.subscribe(sessionId);
    }

    /**
     * 验证聊天请求参数：确保用户已登录且消息非空。
     *
     * @param request 聊天请求
     * @throws CYException 30001-未登录，30004-消息为空
     */
    private void validateRequest(ChatRequest request) {
        if (InvocationContextAccessor.current() == null
                || InvocationContextAccessor.currentUserId() == null) {
            throw new CYException(30001);
        }
        if (StringUtils.isBlank(request.getMessage())) {
            throw new CYException(30004);
        }
    }

    /**
     * 准备会话：获取已有会话或创建新会话，并保存用户消息。
     * <p>
     * 上下文传播由 {@code cy-common-starter-web} 的 Reactor 桥接器自动完成，
     * 无需手动包裹 {@link InvocationContextAccessor#call} 或 {@link InvocationContextAccessor#run}。
     * </p>
     *
     * @param ctx 对话上下文
     * @return 会话实体
     */
    private Mono<ConversationSession> prepareSession(ChatContext ctx) {
        return Mono.fromCallable(() -> {
            ConversationSession session;
            if (ctx.request.getSessionId() != null) {
                session = sessionService.getSessionById(ctx.request.getSessionId());
                if (session == null) {
                    throw new CYException(30002);
                }
            } else {
                session = sessionService.createSession(null);
                ctx.isFirstMessage.set(true);
                ctx.isNewSession.set(true);
            }
            ctx.sessionIdRef.set(session.getId());
            chatMessageService.saveUserMessage(session.getId(), ctx.request.getMessage());
            
            if (!ctx.isFirstMessage.get()) {
                long messageCount = chatMessageService.countBySessionId(session.getId());
                ctx.isFirstMessage.set(messageCount <= 1);
            }
            return session;
        });
    }

    /**
     * 构建消息列表并发起流式对话请求。
     *
     * @param ctx     对话上下文
     * @param session 会话实体
     * @return 流式响应块
     */
    private Flux<ChatCompletionChunk> executeStreamChat(
            ChatContext ctx, ConversationSession session) {
        List<ChatMessage> messages = buildMessages(ctx.request, session.getId());
        
        if (messages.isEmpty()) {
            messages.add(ChatMessage.user(ctx.request.getMessage()));
            log.debug("历史消息为空，手动添加用户消息");
        }
        log.debug("构建请求消息列表，消息数: {}", messages.size());

        ChatCompletionRequest completionRequest = ChatCompletionRequest.builder()
                .model(ctx.request.getModel())
                .messages(messages)
                .temperature(ctx.request.getTemperature())
                .maxTokens(ctx.request.getMaxTokens())
                .build();
        if (ctx.request.getEnableThinking() != null) {
            completionRequest.extra("enable_thinking", ctx.request.getEnableThinking());
        }

        return aiFacade.chatStream(completionRequest);
    }

    /**
     * 处理单个流式响应块，累积完整响应并生成前端可消费的响应对象。
     *
     * @param ctx   对话上下文
     * @param chunk AI 响应块
     * @return 前端响应对象，完成标记块返回 {@code null}
     */
    private ChatStreamResponse processChunk(ChatContext ctx,
            ChatCompletionChunk chunk) {
        if (ctx.isFirstToken.compareAndSet(true, false)) {
            ctx.firstTokenTime.set(System.currentTimeMillis() - ctx.startTime);
            log.debug("首帧响应耗时: {}ms, 会话ID: {}", ctx.firstTokenTime.get(), ctx.sessionIdRef.get());
        }

        // 思考内容片段
        String reasoning = chunk.getReasoningContent();
        if (reasoning != null) {
            ctx.fullReasoningResponse.append(reasoning);
            return ChatStreamResponse.reasoning(reasoning);
        }

        // 正文内容片段
        String content = chunk.getContent();
        if (content != null) {
            ctx.fullResponse.append(content);
            return ChatStreamResponse.content(content);
        }

        if (chunk.isFinished()) {
            return null;
        }
        return ChatStreamResponse.content("");
    }

    /**
     * 完成对话处理：计算统计数据、触发异步保存和智能命名，返回 done 响应。
     *
     * @param ctx 对话上下文
     * @return 包含统计信息的 done 响应
     */
    private Mono<ChatStreamResponse> completeChat(ChatContext ctx) {
        long durationMs = System.currentTimeMillis() - ctx.startTime;
        Long sessionId = ctx.sessionIdRef.get();
        String responseContent = ctx.fullResponse.toString();
        String reasoningContent = ctx.fullReasoningResponse.length() > 0
                ? ctx.fullReasoningResponse.toString() : null;
        int estimatedTokens = (ctx.request.getMessage().length() + responseContent.length()) / 4;

        // 异步保存AI回复
        ctx.saved.set(true);
        saveAssistantMessageAsync(ctx, sessionId, responseContent, reasoningContent,
                estimatedTokens, durationMs);

        return Mono.just(ChatStreamResponse.done(
                sessionId, null, estimatedTokens, ctx.firstTokenTime.get(), durationMs
        ));
    }

    /**
     * 异步保存 AI 回复消息、触发智能命名和记录指标。
     * <p>
     * 在 {@code boundedElastic} 线程池中执行。
     * 上下文传播由 {@code cy-common-starter-web} 的 Reactor 桥接器自动完成。
     * </p>
     *
     * @param ctx              对话上下文
     * @param sessionId        会话 ID
     * @param responseContent  AI 回复内容
     * @param reasoningContent 思考内容（可为 null）
     * @param estimatedTokens  估算 Token 数
     * @param durationMs       总耗时（毫秒）
     */
    private void saveAssistantMessageAsync(ChatContext ctx, Long sessionId,
            String responseContent, String reasoningContent,
            int estimatedTokens, long durationMs) {
        Mono.fromRunnable(() -> {
            try {
                String model = ctx.request.getModel() != null ?
                        ctx.request.getModel() : "default";
                chatMessageService.saveAssistantMessage(
                        sessionId, responseContent, reasoningContent, model,
                        estimatedTokens, durationMs, ctx.firstTokenTime.get()
                );

                if (ctx.isFirstMessage.get()) {
                    sessionService.asyncGenerateTitle(sessionId, ctx.request.getMessage(), responseContent);
                }

                if (metrics != null) {
                    metrics.recordChat(model, estimatedTokens, ctx.firstTokenTime.get(), durationMs);
                }

                log.info("对话完成，会话ID: {}, Token数: {}, 首帧: {}ms, 总耗时: {}ms",
                        sessionId, estimatedTokens, ctx.firstTokenTime.get(), durationMs);
            } catch (Exception e) {
                log.error("保存AI回复失败", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /**
     * 处理流式对话过程中的异常。
     * <p>
     * 如果是新创建的会话且对话失败，会尝试清理该会话，
     * 避免留下空会话。
     * </p>
     *
     * @param e   异常
     * @param ctx 对话上下文
     * @return 包含错误信息的响应流
     */
    private Flux<ChatStreamResponse> handleError(Throwable e, ChatContext ctx) {
        log.error("流式对话失败", e);
        
        // 如果是新创建的会话且对话失败，删除该会话
        if (ctx.isNewSession.get() && ctx.sessionIdRef.get() != null) {
            try {
                sessionService.deleteSession(ctx.sessionIdRef.get());
                log.info("对话失败，已删除新创建的会话: {}", ctx.sessionIdRef.get());
            } catch (Exception deleteEx) {
                log.warn("删除失败会话异常", deleteEx);
            }
        }
        
        return Flux.just(chatStreamErrorSupport.toStreamError(e, STREAM_URI));
    }

    /**
     * 对话上下文（用于在多个方法间传递状态）。
     * <p>
     * 封装一次流式对话过程中的所有可变状态，避免方法签名过长。
     * 使用原子引用确保响应式链路中的线程安全。
     * </p>
     */
    private static class ChatContext {
        /** 原始聊天请求 */
        final ChatRequest request;
        /** 对话开始时间戳 */
        final long startTime = System.currentTimeMillis();
        /** 首帧响应耗时（毫秒） */
        final AtomicLong firstTokenTime = new AtomicLong(0);
        /** 是否尚未收到首个 Token */
        final AtomicBoolean isFirstToken = new AtomicBoolean(true);
        /** 是否为会话的首条消息（用于触发智能命名） */
        final AtomicBoolean isFirstMessage = new AtomicBoolean(false);
        /** 是否为新创建的会话（失败时需要清理） */
        final AtomicBoolean isNewSession = new AtomicBoolean(false);
        /** AI 回复是否已保存 */
        final AtomicBoolean saved = new AtomicBoolean(false);
        /** 会话 ID 引用，可能在创建会话后更新 */
        final AtomicReference<Long> sessionIdRef;
        /** 累积的 AI 回复正文 */
        final StringBuilder fullResponse = new StringBuilder();
        /** 累积的 AI 思考内容 */
        final StringBuilder fullReasoningResponse = new StringBuilder();

        /**
         * 构造对话上下文。
         *
         * @param request 聊天请求
         */
        ChatContext(ChatRequest request) {
            this.request = request;
            this.sessionIdRef = new AtomicReference<>(request.getSessionId());
        }
    }

    /**
     * 构建发送给大模型的消息列表
     * <p>
     * 包含系统提示（如果有）和历史对话上下文
     * </p>
     *
     * @param request   聊天请求
     * @param sessionId 会话ID
     * @return 消息列表
     */
    private List<ChatMessage> buildMessages(ChatRequest request, Long sessionId) {
        List<ChatMessage> messages = new ArrayList<>();

        // 添加系统提示（如果有）
        if (StringUtils.isNotBlank(request.getSystemPrompt())) {
            messages.add(ChatMessage.system(request.getSystemPrompt()));
        }

        // 获取历史消息数量限制
        int historyLimit = request.getHistoryLimit() != null ?
                request.getHistoryLimit() : DEFAULT_HISTORY_LIMIT;

        // 获取历史消息并转换为AI消息格式
        List<com.cy.conversation.message.entity.ChatMessage> historyMessages =
                chatMessageService.getRecentMessages(sessionId, historyLimit);
        for (com.cy.conversation.message.entity.ChatMessage msg : historyMessages) {
            // 跳过系统消息（因为我们会单独添加）
            if (com.cy.conversation.message.entity.ChatMessage.ROLE_SYSTEM.equals(msg.getRole())) {
                continue;
            }
            messages.add(ChatMessage.builder().role(msg.getRole()).content(msg.getContent()).build());
        }

        // 添加当前用户消息（如果历史消息中没有包含）
        // 注意：历史消息中已经包含了刚保存的用户消息，所以这里不需要再添加

        return messages;
    }
}
