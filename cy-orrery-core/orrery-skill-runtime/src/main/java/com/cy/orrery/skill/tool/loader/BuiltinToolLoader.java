// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.tool.loader;

import com.cy.orrery.skill.tool.BuiltinTool;
import com.cy.orrery.skill.tool.builtin.FileEditTool;
import com.cy.orrery.skill.tool.builtin.FileReadTool;
import com.cy.orrery.skill.tool.builtin.FileWriteTool;
import com.cy.orrery.skill.tool.builtin.MemorySearchTool;
import com.cy.orrery.skill.tool.builtin.ProcessManageTool;
import com.cy.orrery.skill.tool.builtin.ShellExecTool;
import com.cy.orrery.skill.tool.builtin.SystemInfoTool;
import com.cy.orrery.skill.tool.builtin.WebFetchTool;
import com.cy.orrery.skill.tool.registry.BuiltinToolRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 内置工具加载器
 * <p>
 * 负责发现并加载所有内置工具到 {@link BuiltinToolRegistry}。
 * 支持两种发现方式：
 * <ul>
 *   <li>硬编码注册 — 加载平台预置的核心内置工具</li>
 *   <li>Java SPI — 通过 {@code META-INF/services/com.cy.orrery.skill.tool.BuiltinTool} 发现扩展工具</li>
 * </ul>
 * 参考 OpenClaw 的 {@code createOpenClawCodingTools()} 组合模式，
 * 加载完成后所有工具统一注册到 BuiltinToolRegistry。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Slf4j
public class BuiltinToolLoader {

    private final BuiltinToolRegistry registry;

    /**
     * 构造加载器。
     *
     * @param registry 工具注册表
     */
    public BuiltinToolLoader(BuiltinToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 加载所有内置工具（核心 + SPI 扩展）
     *
     * @return 成功加载的工具数量
     */
    public int loadAll() {
        int count = 0;

        // 1. 加载核心内置工具
        count += loadCoreTools();

        // 2. 通过 SPI 发现扩展工具
        count += loadSpiTools();

        log.info("内置工具加载完成: 共 {} 个", count);
        return count;
    }

    /**
     * 加载平台预置的核心内置工具
     *
     * @return 成功加载数量
     */
    public int loadCoreTools() {
        List<BuiltinTool> coreTools = createCoreTools();
        int count = registerAll(coreTools, "核心");
        log.info("核心内置工具加载完成: {}/{}", count, coreTools.size());
        return count;
    }

    /**
     * 通过 SPI 发现并加载扩展内置工具
     *
     * @return 成功加载数量
     */
    public int loadSpiTools() {
        int count = 0;
        ServiceLoader<BuiltinTool> serviceLoader = ServiceLoader.load(BuiltinTool.class);
        for (BuiltinTool tool : serviceLoader) {
            count += registerOne(tool, "SPI");
        }
        if (count > 0) {
            log.info("SPI 扩展工具加载完成: {} 个", count);
        }
        return count;
    }

    /**
     * 手动注册外部提供的内置工具（如 Spring Bean）
     *
     * @param tools 外部工具列表
     * @return 成功加载数量
     */
    public int loadExternalTools(List<BuiltinTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        int count = registerAll(tools, "外部");
        log.info("外部工具加载完成: {}/{}", count, tools.size());
        return count;
    }

    /** 批量注册（逐工具隔离失败：单个注册异常不阻断其余）。 */
    private int registerAll(List<BuiltinTool> tools, String sourceLabel) {
        int count = 0;
        for (BuiltinTool tool : tools) {
            count += registerOne(tool, sourceLabel);
        }
        return count;
    }

    /** 注册单个工具（已注册跳过；失败记 error 并返回 0）。 */
    private int registerOne(BuiltinTool tool, String sourceLabel) {
        try {
            if (registry.contains(tool.getSkillId())) {
                log.warn("{}工具已注册，跳过: toolId={}", sourceLabel, tool.getSkillId());
                return 0;
            }
            registry.register(tool);
            return 1;
        } catch (RuntimeException e) {
            log.error("{}工具注册失败: toolId={}", sourceLabel, tool.getSkillId(), e);
            return 0;
        }
    }

    /**
     * 创建平台预置的核心内置工具
     * <p>
     * 核心工具集（含高层级工具 system_info）：
     * <ul>
     *   <li>file_read → read</li>
     *   <li>file_write → write</li>
     *   <li>file_edit → edit</li>
     *   <li>shell_exec → exec</li>
     *   <li>process_manage → process</li>
     *   <li>memory_search → memory_search</li>
     *   <li>web_fetch → web_fetch</li>
     *   <li>system_info → 一次收集完整系统信息（Anthropic 高层级工具原则）</li>
     * </ul>
     * </p>
     */
    private List<BuiltinTool> createCoreTools() {
        List<BuiltinTool> tools = new ArrayList<>();
        tools.add(new FileReadTool());
        tools.add(new FileWriteTool());
        tools.add(new FileEditTool());
        tools.add(new ShellExecTool());
        tools.add(new ProcessManageTool());
        tools.add(new MemorySearchTool());
        tools.add(new WebFetchTool());
        tools.add(new SystemInfoTool());
        return tools;
    }
}
