package com.cy.conversation.message.controller;

import com.cy.conversation.message.entity.ChatMessage;
import com.cy.conversation.message.service.ChatMessageService;
import com.cy.core.base.R;
import com.mybatisflex.core.paginate.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 聊天消息控制器
 * <p>
 * 提供聊天记录的查询REST API接口
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
@RestController
@RequestMapping("/conversation/message")
@Validated
@Tag(name = "聊天记录管理")
@RequiredArgsConstructor
public class ChatMessageController {

    private final ChatMessageService chatMessageService;

    /**
     * 根据会话ID查询消息列表
     *
     * @param sessionId 会话ID
     * @return 消息列表
     */
    @Operation(summary = "查询会话消息列表", description = "根据会话ID查询所有消息，按时间正序排列")
    @GetMapping("/list")
    public R<List<ChatMessage>> list(
            @Parameter(description = "会话ID", required = true) @RequestParam Long sessionId) {
        return R.ok(chatMessageService.listBySessionId(sessionId));
    }

    /**
     * 分页查询会话消息
     *
     * @param sessionId 会话ID
     * @param page      页码
     * @param size      每页大小
     * @return 分页结果
     */
    @Operation(summary = "分页查询会话消息")
    @GetMapping("/page")
    public R<Page<ChatMessage>> page(
            @Parameter(description = "会话ID", required = true) @RequestParam Long sessionId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "20") int size) {
        return R.ok(chatMessageService.pageBySessionId(sessionId, page, size));
    }

    /**
     * 获取会话最近消息
     *
     * @param sessionId 会话ID
     * @param limit     最大消息数
     * @return 消息列表
     */
    @Operation(summary = "获取会话最近消息", description = "获取会话的最近N条消息，用于构建对话上下文")
    @GetMapping("/recent")
    public R<List<ChatMessage>> recent(
            @Parameter(description = "会话ID", required = true) @RequestParam Long sessionId,
            @Parameter(description = "最大消息数") @RequestParam(defaultValue = "10") int limit) {
        return R.ok(chatMessageService.getRecentMessages(sessionId, limit));
    }
}
