// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.tool;

import com.cy.orrery.protocol.enums.SideEffectLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * 工具策略
 * <p>
 * 参考 OpenClaw 的 ToolPolicy（allow/deny 列表 + 分类级控制），
 * 控制内置工具对特定调用方的可见性和可执行性。
 * Butler 和 Agent 各自持有独立的 ToolPolicy 实例。
 * </p>
 * <p>
 * 判定规则（对齐 OpenClaw + MCP annotations）：
 * <ol>
 *   <li>deny 列表优先：命中 deny 则拒绝</li>
 *   <li>分类级 deny 覆盖所有该分类下的工具</li>
 *   <li>ownerOnly 检查：非 owner 身份拒绝 ownerOnly 工具</li>
 *   <li>副作用级别检查：超过 maxAllowedSideEffectLevel 的工具被拒绝</li>
 *   <li>分类级 allow（非空时，工具分类必须在 allowedCategories 内）</li>
 *   <li>allow 列表为空时默认全部放行</li>
 *   <li>allow 列表非空时，仅放行 allow 内的工具</li>
 * </ol>
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolPolicy {

    /**
     * 允许使用的工具ID集合（空集表示全部允许）
     */
    @Builder.Default
    private Set<String> allowedToolIds = new HashSet<>();

    /**
     * 拒绝使用的工具ID集合（优先于 allow）
     */
    @Builder.Default
    private Set<String> deniedToolIds = new HashSet<>();

    /**
     * 允许使用的工具分类集合（空集表示不按分类限制）
     */
    @Builder.Default
    private Set<ToolCategory> allowedCategories = new HashSet<>();

    /**
     * 拒绝使用的工具分类集合（优先于 allowedCategories）
     */
    @Builder.Default
    private Set<ToolCategory> deniedCategories = new HashSet<>();

    /**
     * 是否允许 ownerOnly 工具
     */
    @Builder.Default
    private boolean ownerAccess = false;

    /**
     * 允许的最大副作用级别
     * <p>
     * 对齐 MCP 协议的 {@code destructiveHint} / {@code readOnlyHint} annotations。
     * 工具的 {@link BuiltinTool#getSideEffectLevel()} 超过此值时被拒绝。
     * 为 null 时不做副作用级别限制。
     * </p>
     * <p>
     * 示例：设置为 {@link SideEffectLevel#LOW} 时，{@code HIGH} 级工具（如 shell_exec）被拒绝。
     * </p>
     */
    private SideEffectLevel maxAllowedSideEffectLevel;

    /**
     * 判断指定工具是否被当前策略允许
     *
     * @param tool 内置工具
     * @return 是否允许执行
     */
    public boolean isAllowed(BuiltinTool tool) {
        if (tool == null) {
            return false;
        }

        String toolId = tool.getSkillId();

        // 1. deny 列表优先
        if (deniedToolIds.contains(toolId)) {
            log.debug("工具被 deny 列表拒绝: toolId={}", toolId);
            return false;
        }

        // 2. 分类级 deny
        if (deniedCategories.contains(tool.getCategory())) {
            log.debug("工具被分类级 deny 拒绝: toolId={}, category={}", toolId, tool.getCategory());
            return false;
        }

        // 3. ownerOnly 检查
        if (tool.isOwnerOnly() && !ownerAccess) {
            log.debug("ownerOnly 工具被拒绝: toolId={}", toolId);
            return false;
        }

        // 4. 副作用级别检查（对齐 MCP destructiveHint）
        if (maxAllowedSideEffectLevel != null
                && tool.getSideEffectLevel().ordinal() > maxAllowedSideEffectLevel.ordinal()) {
            log.debug("工具副作用级别超限被拒绝: toolId={}, toolLevel={}, maxLevel={}",
                    toolId, tool.getSideEffectLevel(), maxAllowedSideEffectLevel);
            return false;
        }

        // 5. 分类级 allow（非空时，工具分类必须在 allowedCategories 内）
        if (!allowedCategories.isEmpty() && !allowedCategories.contains(tool.getCategory())) {
            log.debug("工具分类未在 allowedCategories 中: toolId={}, category={}", toolId, tool.getCategory());
            return false;
        }

        // 6. allow 列表为空 → 全部放行
        if (allowedToolIds.isEmpty()) {
            return true;
        }

        // 7. allow 列表非空 → 仅放行 allow 内的
        boolean allowed = allowedToolIds.contains(toolId);
        if (!allowed) {
            log.debug("工具未在 allow 列表中: toolId={}", toolId);
        }
        return allowed;
    }

    /**
     * 创建全放行策略（无限制）
     *
     * @return 全放行策略
     */
    public static ToolPolicy allowAll() {
        return ToolPolicy.builder().ownerAccess(true).build();
    }

    /**
     * 创建全拒绝策略
     *
     * @return 全拒绝策略（通过 deny 所有分类实现）
     */
    public static ToolPolicy denyAll() {
        return ToolPolicy.builder()
                .deniedCategories(EnumSet.allOf(ToolCategory.class))
                .build();
    }
}
