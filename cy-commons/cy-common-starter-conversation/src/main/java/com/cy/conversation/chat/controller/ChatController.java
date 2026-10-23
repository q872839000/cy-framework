package com.cy.conversation.chat.controller;

import com.cy.conversation.chat.service.ChatService;
import com.cy.conversation.chat.support.ChatStreamErrorSupport;
import com.cy.conversation.chat.vo.ChatRequest;
import com.cy.conversation.chat.vo.ChatStreamResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.cy.core.base.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.function.Supplier;

/**
 * 聊天控制器
 * <p>
 * 提供与大模型对话的REST API接口，支持流式输出
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
@RestController
@RequestMapping("/conversation/chat")
@Validated
@Tag(name = "智能对话")
@RequiredArgsConstructor
public class ChatController {

    private static final String STREAM_URI = "/conversation/chat/stream";
    private static final String RESUME_STREAM_URI = "/conversation/chat/stream/resume";

    private final ChatService chatService;
    private final ChatStreamErrorSupport chatStreamErrorSupport;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 流式对话
     * <p>
     * 发送用户消息并通过SSE实时返回AI响应片段。
     * 响应类型包括：
     * - content: 内容片段
     * - done: 完成标记，包含会话ID、Token消耗等信息
     * - error: 错误信息
     * </p>
     *
     * @param request 聊天请求
     * @return SSE流式响应
     */
    @Operation(summary = "流式对话", description = "发送用户消息并实时返回AI响应，支持SSE流式输出")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@Valid @RequestBody ChatRequest request) {
        return toEventStream(() -> chatService.streamChat(request), STREAM_URI);
    }

    /**
     * 检查指定会话是否有活跃的流式输出
     *
     * @param sessionId 会话ID
     * @return 是否活跃
     */
    @Operation(summary = "检查活跃流", description = "检查指定会话是否有正在进行的流式输出")
    @GetMapping("/stream/active/{sessionId}")
    public R<Boolean> isStreamActive(@PathVariable Long sessionId) {
        return R.ok(chatService.isStreamActive(sessionId));
    }

    /**
     * 重连到活跃的流式会话
     * <p>
     * 客户端断连后重新订阅，replay所有已缓存的块 + 后续实时块。
     * </p>
     *
     * @param sessionId 会话ID
     * @return SSE流式响应
     */
    @Operation(summary = "重连流式会话", description = "断连后重新订阅活跃的流式输出")
    @GetMapping(value = "/stream/resume/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> resumeStream(@PathVariable Long sessionId) {
        return toEventStream(() -> chatService.resumeStream(sessionId), RESUME_STREAM_URI);
    }

    private Flux<ServerSentEvent<String>> toEventStream(Supplier<Flux<ChatStreamResponse>> streamSupplier,
                                                        String uri) {
        return Flux.defer(streamSupplier::get)
                .map(this::toServerSentEvent)
                .onErrorResume(e -> Flux.just(toServerSentEvent(toErrorResponse(e, uri))));
    }

    private ChatStreamResponse toErrorResponse(Throwable e, String uri) {
        return chatStreamErrorSupport.toStreamError(e, uri);
    }

    /**
     * 将响应转换为ServerSentEvent
     *
     * @param response 聊天流式响应
     * @return SSE事件
     */
    private ServerSentEvent<String> toServerSentEvent(ChatStreamResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            return ServerSentEvent.<String>builder()
                    .data(json)
                    .build();
        } catch (JsonProcessingException e) {
            return ServerSentEvent.<String>builder()
                    .data("{\"type\":\"error\",\"error\":\"序列化失败\"}")
                    .build();
        }
    }
}
