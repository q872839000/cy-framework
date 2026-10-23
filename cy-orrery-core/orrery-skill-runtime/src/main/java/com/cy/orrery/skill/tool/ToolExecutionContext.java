// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.Map;

/**
 * 工具执行上下文
 * <p>
 * 封装工具执行时所需的运行时环境信息，包括工作目录、沙箱约束、
 * 调用者身份、超时设置等。每次工具执行时由调用方构建并传入，
 * 工具实现据此决定执行策略和安全边界。
 * </p>
 * <p>
 * <b>安全默认值设计（Defense in Depth）：</b>
 * <ul>
 *   <li>{@code sandboxed} 默认 {@code true} — 即使调用方遗漏设置，也默认启用沙箱</li>
 *   <li>{@code ownerAccess} 默认 {@code false} — 最小权限原则</li>
 *   <li>{@code timeoutMs} 默认 30 秒 — 防止工具执行无限阻塞</li>
 *   <li>{@code maxOutputBytes} 默认 512KB — 防止输出耗尽内存</li>
 * </ul>
 * 调用方可通过 {@link com.cy.orrery.skill.tool.security.ToolSecurityPolicy#enrichContext}
 * 统一补全缺失字段，无需手动逐一设置。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionContext {

    /**
     * 工作目录（工具操作的根路径）
     */
    private Path workingDirectory;

    /**
     * 是否处于沙箱模式（限制文件系统访问范围）
     * <p>
     * <b>安全默认值 {@code true}</b>：即使调用方忘记设置，
     * 文件和命令类工具也会执行沙箱路径校验。
     * 仅在明确知晓风险的场景下将其设置为 {@code false}。
     * </p>
     */
    @Builder.Default
    private boolean sandboxed = true;

    /**
     * 沙箱根路径（所有文件/命令操作必须限制在此目录内）
     * <p>
     * 当 {@code sandboxed=true} 时此字段不应为 null。
     * {@link com.cy.orrery.skill.tool.security.ToolSecurityPolicy} 会在执行前
     * 校验此约束，未满足时拒绝执行。
     * </p>
     */
    private Path sandboxRoot;

    /**
     * 调用者身份标识（agentId 或 sessionKey，用于审计和权限判定）
     */
    private String callerId;

    /**
     * 调用者是否为 owner（控制 ownerOnly 工具的访问权限）
     * <p>
     * <b>安全默认值 {@code false}</b>：非 owner 身份无法调用
     * {@link BuiltinTool#isOwnerOnly()} 标记为 true 的高权限工具。
     * </p>
     */
    @Builder.Default
    private boolean ownerAccess = false;

    /**
     * 执行超时（毫秒），0 表示不限
     * <p>
     * 默认 30 秒。{@link com.cy.orrery.skill.tool.security.ToolSecurityPolicy}
     * 会将此值截断到全局上限 {@code maxTimeoutMs}。
     * </p>
     */
    @Builder.Default
    private long timeoutMs = 30_000L;

    /**
     * 最大输出字节数（防止工具输出耗尽内存）
     */
    @Builder.Default
    private int maxOutputBytes = 512 * 1024;

    /**
     * 环境变量覆盖（注入到子进程的环境变量）
     */
    private Map<String, String> envOverrides;
}
