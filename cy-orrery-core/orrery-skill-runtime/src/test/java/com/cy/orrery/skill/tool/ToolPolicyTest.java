package com.cy.orrery.skill.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolPolicy 单元测试
 *
 * @author pcy
 * @date 2026/03/14
 */
class ToolPolicyTest {

    private BuiltinTool mockTool(String id, ToolCategory category, boolean ownerOnly) {
        return new BuiltinTool() {
            @Override public String getSkillId() { return id; }
            @Override public String getName() { return id; }
            @Override public ToolCategory getCategory() { return category; }
            @Override public String getParameterSchema() { return "{}"; }
            @Override public boolean isOwnerOnly() { return ownerOnly; }
            @Override public java.util.Map<String, Object> execute(
                    java.util.Map<String, Object> input, ToolExecutionContext context) {
                return java.util.Map.of();
            }
        };
    }

    @Test
    @DisplayName("allowAll 策略放行所有工具（含 ownerOnly）")
    void allowAll_permitsEverything() {
        ToolPolicy policy = ToolPolicy.allowAll();
        assertTrue(policy.isAllowed(mockTool("file-read", ToolCategory.FILESYSTEM, false)));
        assertTrue(policy.isAllowed(mockTool("shell-exec", ToolCategory.EXECUTION, true)));
    }

    @Test
    @DisplayName("denyAll 策略拒绝所有工具")
    void denyAll_rejectsEverything() {
        ToolPolicy policy = ToolPolicy.denyAll();
        assertFalse(policy.isAllowed(mockTool("file-read", ToolCategory.FILESYSTEM, false)));
        assertFalse(policy.isAllowed(mockTool("web-fetch", ToolCategory.NETWORK, false)));
    }

    @Test
    @DisplayName("deny 列表优先于 allow 列表")
    void denyOverridesAllow() {
        ToolPolicy policy = ToolPolicy.builder()
                .allowedToolIds(Set.of("file-read", "shell-exec"))
                .deniedToolIds(Set.of("shell-exec"))
                .build();
        assertTrue(policy.isAllowed(mockTool("file-read", ToolCategory.FILESYSTEM, false)));
        assertFalse(policy.isAllowed(mockTool("shell-exec", ToolCategory.EXECUTION, false)));
    }

    @Test
    @DisplayName("分类级 deny 覆盖所有该分类下的工具")
    void deniedCategory_rejectsAllToolsInCategory() {
        ToolPolicy policy = ToolPolicy.builder()
                .deniedCategories(EnumSet.of(ToolCategory.EXECUTION))
                .build();
        assertFalse(policy.isAllowed(mockTool("shell-exec", ToolCategory.EXECUTION, false)));
        assertTrue(policy.isAllowed(mockTool("file-read", ToolCategory.FILESYSTEM, false)));
    }

    @Test
    @DisplayName("allowedCategories 非空时，仅放行指定分类")
    void allowedCategories_onlyPermitsSpecifiedCategories() {
        ToolPolicy policy = ToolPolicy.builder()
                .allowedCategories(EnumSet.of(ToolCategory.FILESYSTEM, ToolCategory.MEMORY))
                .build();
        assertTrue(policy.isAllowed(mockTool("file-read", ToolCategory.FILESYSTEM, false)));
        assertTrue(policy.isAllowed(mockTool("mem-search", ToolCategory.MEMORY, false)));
        assertFalse(policy.isAllowed(mockTool("shell-exec", ToolCategory.EXECUTION, false)));
    }

    @Test
    @DisplayName("ownerOnly 工具在 ownerAccess=false 时被拒绝")
    void ownerOnly_rejectedWithoutOwnerAccess() {
        ToolPolicy policy = ToolPolicy.builder().ownerAccess(false).build();
        assertFalse(policy.isAllowed(mockTool("shell-exec", ToolCategory.EXECUTION, true)));
        assertTrue(policy.isAllowed(mockTool("file-read", ToolCategory.FILESYSTEM, false)));
    }

    @Test
    @DisplayName("null 工具返回 false")
    void nullTool_returnsFalse() {
        assertFalse(ToolPolicy.allowAll().isAllowed(null));
    }
}
