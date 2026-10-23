// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.tool.registry;

import com.cy.orrery.skill.tool.BuiltinTool;
import com.cy.orrery.skill.tool.ToolCategory;
import com.cy.orrery.skill.tool.ToolPolicy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内置工具注册表
 * <p>
 * 独立于 {@link com.cy.orrery.skill.registry.SkillRegistry}，专门管理内置工具的注册与检索。
 * 参考 OpenClaw 的工具组合模式：注册表持有所有已注册工具，调用方通过 ToolPolicy 过滤后
 * 获取可用工具列表，供 LLM function-calling 或 Butler 编排使用。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Slf4j
public class BuiltinToolRegistry {

    /**
     * 已注册的内置工具映射（toolId → BuiltinTool）
     */
    private final Map<String, BuiltinTool> tools = new ConcurrentHashMap<>();

    /**
     * 注册内置工具
     *
     * @param tool 内置工具实例
     */
    public void register(BuiltinTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("BuiltinTool 不能为空");
        }
        String toolId = tool.getSkillId();
        tools.put(toolId, tool);
        log.info("内置工具注册成功: toolId={}, name={}, category={}",
                toolId, tool.getName(), tool.getCategory());
    }

    /**
     * 注销内置工具
     *
     * @param toolId 工具ID
     */
    public void unregister(String toolId) {
        BuiltinTool removed = tools.remove(toolId);
        if (removed != null) {
            log.info("内置工具已注销: toolId={}", toolId);
        }
    }

    /**
     * 根据 toolId 获取工具实例
     *
     * @param toolId 工具ID
     * @return 工具实例，不存在返回 null
     */
    public BuiltinTool getTool(String toolId) {
        return tools.get(toolId);
    }

    /**
     * 获取所有已注册的内置工具
     *
     * @return 工具列表
     */
    public List<BuiltinTool> listAll() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 根据分类获取工具列表
     *
     * @param category 工具分类
     * @return 该分类下的工具列表
     */
    public List<BuiltinTool> listByCategory(ToolCategory category) {
        return tools.values().stream()
                .filter(tool -> tool.getCategory() == category)
                .collect(Collectors.toList());
    }

    /**
     * 应用策略过滤，获取当前策略下可用的工具列表
     * <p>
     * 参考 OpenClaw 的 {@code applyToolPolicyPipeline}：遍历所有工具，
     * 逐一检查 ToolPolicy，仅返回策略允许的工具。
     * </p>
     *
     * @param policy 工具策略
     * @return 策略允许的工具列表
     */
    public List<BuiltinTool> listAllowed(ToolPolicy policy) {
        if (policy == null) {
            return listAll();
        }
        return tools.values().stream()
                .filter(policy::isAllowed)
                .collect(Collectors.toList());
    }

    /**
     * 检查工具是否已注册
     *
     * @param toolId 工具ID
     * @return 是否已注册
     */
    public boolean contains(String toolId) {
        return tools.containsKey(toolId);
    }

    /**
     * 获取已注册工具数量
     *
     * @return 数量
     */
    public int size() {
        return tools.size();
    }
}
