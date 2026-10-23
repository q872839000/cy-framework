package com.cy.conversation.session.controller;

import com.cy.conversation.session.entity.ConversationSession;
import com.cy.conversation.session.service.SessionService;
import com.cy.log.annotation.SysLog;
import com.cy.conversation.session.vo.SessionModelPreferenceRequest;
import com.cy.conversation.session.vo.SessionQueryCondition;
import com.cy.conversation.session.vo.SessionUpdateRequest;
import com.cy.core.base.R;
import com.mybatisflex.core.paginate.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话管理控制器
 * <p>
 * 提供会话的增删改查REST API接口
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
@RestController
@RequestMapping("/conversation/session")
@Validated
@Tag(name = "会话管理")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    /**
     * 更新会话标题
     *
     * @param request 更新请求
     * @return 是否更新成功
     */
    @Operation(summary = "更新会话标题")
    @PostMapping("/update")
    @SysLog(module = "会话管理", operation = "更新会话标题", targetType = "Session", targetIdEl = "#request.id")
    public R<Boolean> update(@Valid @RequestBody SessionUpdateRequest request) {
        return R.ok(sessionService.updateTitle(request.getId(), request.getTitle()));
    }

    /**
     * 更新会话模型偏好
     *
     * @param request 更新请求（provider/model 为 null = 清除覆盖、跟随平台默认）
     * @return 是否更新成功
     */
    @Operation(summary = "更新会话模型偏好", description = "会话级 LLM 提供商/模型覆盖；null = 清除覆盖、跟随平台默认")
    @PostMapping("/model")
    @SysLog(module = "会话管理", operation = "更新会话模型偏好", targetType = "Session", targetIdEl = "#request.id")
    public R<Boolean> updateModelPreference(@Valid @RequestBody SessionModelPreferenceRequest request) {
        return R.ok(sessionService.updateModelPreference(
                request.getId(), request.getLlmProvider(), request.getLlmModel(), request.getThinkingPref()));
    }

    /**
     * 获取会话详情
     *
     * @param id 会话ID
     * @return 会话详情
     */
    @Operation(summary = "获取会话详情")
    @GetMapping("/get")
    public R<ConversationSession> get(@Parameter(description = "会话ID") @RequestParam Long id) {
        return R.ok(sessionService.getSessionById(id));
    }

    /**
     * 删除会话
     *
     * @param id 会话ID
     * @return 是否删除成功
     */
    @Operation(summary = "删除会话")
    @PostMapping("/delete")
    @SysLog(module = "会话管理", operation = "删除会话", targetType = "Session", targetIdEl = "#id")
    public R<Boolean> delete(@Parameter(description = "会话ID") @RequestParam Long id) {
        return R.ok(sessionService.deleteSession(id));
    }

    /**
     * 分页查询会话列表
     *
     * @param condition 查询条件
     * @return 分页结果
     */
    @Operation(summary = "分页查询会话列表", description = "查询当前用户的会话列表，支持标题模糊查询和状态筛选")
    @GetMapping("/page")
    public R<Page<ConversationSession>> page(SessionQueryCondition condition) {
        return R.ok(sessionService.pageUserSessions(condition));
    }

    /**
     * 查询所有会话列表
     *
     * @return 会话列表
     */
    @Operation(summary = "查询所有会话列表", description = "查询当前用户的所有正常状态会话")
    @GetMapping("/list")
    public R<List<ConversationSession>> list() {
        return R.ok(sessionService.listUserSessions());
    }
}
