package com.cy.conversation.chat.service;

import com.cy.conversation.chat.vo.ChatStreamResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 流式会话管理器
 * <p>
 * 管理活跃的流式对话，实现以下功能：
 * - 将上游AI流与下游SSE连接解耦
 * - 支持客户端断连后流继续运行
 * - 支持客户端重连时接续流输出（replay所有已缓存的块）
 * </p>
 *
 * @author pcy
 * @date 2026/3/11
 */
@Component
public class StreamSessionManager {

    private static final Logger log = LoggerFactory.getLogger(StreamSessionManager.class);

    private final ConcurrentHashMap<Long, Sinks.Many<ChatStreamResponse>> activeStreams =
            new ConcurrentHashMap<>();

    /**
     * 将已有 sink 注册到指定会话
     *
     * @param sessionId 会话ID
     * @param sink      外部创建的 replay sink
     */
    public void register(Long sessionId, Sinks.Many<ChatStreamResponse> sink) {
        activeStreams.put(sessionId, sink);
        log.debug("注册活跃流，会话ID: {}", sessionId);
    }

    /**
     * 检查指定会话是否有活跃的流
     *
     * @param sessionId 会话ID
     * @return 是否活跃
     */
    public boolean isActive(Long sessionId) {
        return sessionId != null && activeStreams.containsKey(sessionId);
    }

    /**
     * 订阅指定会话的活跃流（replay所有已缓存 + 后续实时块）
     *
     * @param sessionId 会话ID
     * @return 流式响应 Flux，如果不存在则返回空 Flux
     */
    public Flux<ChatStreamResponse> subscribe(Long sessionId) {
        Sinks.Many<ChatStreamResponse> sink = activeStreams.get(sessionId);
        if (sink == null) {
            return Flux.empty();
        }
        return sink.asFlux();
    }

    /**
     * 移除指定会话的活跃流
     *
     * @param sessionId 会话ID
     */
    public void remove(Long sessionId) {
        if (sessionId != null) {
            activeStreams.remove(sessionId);
            log.debug("移除活跃流，会话ID: {}", sessionId);
        }
    }

    /**
     * 获取当前活跃流数量（用于监控）
     *
     * @return 活跃流数量
     */
    public int activeCount() {
        return activeStreams.size();
    }
}
