// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.tool.security;

import com.cy.orrery.skill.tool.BuiltinTool;
import com.cy.orrery.skill.tool.ToolCategory;
import com.cy.orrery.skill.tool.ToolExecutionContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具安全策略
 * <p>
 * 平台级安全策略组件，控制内置工具执行的安全边界。
 * 该策略在 orrery-skill-runtime 层面统一管控，不依赖调用方（Butler / Agent）
 * 是否"正确传入"安全参数，实现纵深防御（Defense in Depth）。
 * </p>
 * <p>
 * 安全策略包含三个维度：
 * <ul>
 *   <li><b>沙箱策略</b>：强制要求 FILESYSTEM / EXECUTION 类工具必须在沙箱内执行</li>
 *   <li><b>命令过滤</b>：基于正则表达式的命令白名单/黑名单机制</li>
 *   <li><b>超时限制</b>：全局超时上限，防止单个工具执行阻塞编排循环</li>
 * </ul>
 * </p>
 * <p>
 * 命令过滤规则执行顺序：
 * <ol>
 *   <li>黑名单优先——命中 {@code deniedCommandPatterns} 则拒绝</li>
 *   <li>白名单为空时默认放行</li>
 *   <li>白名单非空时，仅放行匹配 {@code allowedCommandPatterns} 的命令</li>
 * </ol>
 * </p>
 *
 * @author pcy
 * @date 2026/03/21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolSecurityPolicy {

    private static final Logger log = LoggerFactory.getLogger(ToolSecurityPolicy.class);

    /** 日志中命令文本的截断长度。 */
    private static final int LOG_COMMAND_MAX_LEN = 120;

    /** 宽松策略的全局超时上限（毫秒）。 */
    private static final long PERMISSIVE_MAX_TIMEOUT_MS = 300_000L;

    /** 宽松策略的默认超时（毫秒）。 */
    private static final long PERMISSIVE_DEFAULT_TIMEOUT_MS = 60_000L;

    // ==================== 沙箱策略 ====================

    /**
     * 必须启用沙箱的工具分类集合
     * <p>
     * 属于这些分类的工具在执行前会被强制校验 {@link ToolExecutionContext#isSandboxed()}
     * 和 {@link ToolExecutionContext#getSandboxRoot()} 是否已正确设置。
     * 若未设置，执行将被拒绝并抛出 {@link SecurityException}。
     * </p>
     */
    @Builder.Default
    private Set<ToolCategory> sandboxRequiredCategories = EnumSet.of(
            ToolCategory.FILESYSTEM, ToolCategory.EXECUTION
    );

    /**
     * 默认沙箱根路径
     * <p>
     * 当调用方未显式指定 sandboxRoot 时使用的兜底路径。
     * 生产环境应配置为隔离的临时工作目录（如 /tmp/orrery-sandbox）。
     * 为 null 时表示未配置默认沙箱——此时属于 {@code sandboxRequiredCategories}
     * 的工具如果上下文也未设置 sandboxRoot，执行将被拒绝。
     * </p>
     */
    private Path defaultSandboxRoot;

    /**
     * 默认工作目录
     * <p>
     * 当调用方未显式指定 workingDirectory 时使用的兜底路径。
     * 通常与 {@code defaultSandboxRoot} 保持一致或为其子目录。
     * </p>
     */
    private Path defaultWorkingDirectory;

    // ==================== 命令过滤 ====================

    /**
     * 命令黑名单（正则表达式）
     * <p>
     * 匹配到的命令将被直接拒绝执行。黑名单优先于白名单。
     * 每个 Pattern 对命令全文进行 {@code matcher.find()} 匹配。
     * </p>
     */
    @Builder.Default
    private List<Pattern> deniedCommandPatterns = new ArrayList<>();

    /**
     * 命令白名单（正则表达式）
     * <p>
     * 为空时不做白名单限制（仅受黑名单约束）。
     * 非空时命令必须至少匹配一个白名单 Pattern 才允许执行。
     * </p>
     */
    @Builder.Default
    private List<Pattern> allowedCommandPatterns = new ArrayList<>();

    /**
     * 危险命令关键词列表（简单子串匹配，补充正则黑名单）
     * <p>
     * 用于拦截常见的高危命令模式，如 {@code rm -rf /}、{@code mkfs}、{@code shutdown} 等。
     * 匹配时忽略大小写。
     * </p>
     */
    @Builder.Default
    private List<String> blockedCommandKeywords = List.of(
            "rm -rf /", "rm -rf /*",
            "mkfs", "dd if=",
            ":(){ :|:& };:",
            "chmod -R 777 /",
            "shutdown", "reboot", "halt", "poweroff",
            "init 0", "init 6",
            "> /dev/sda", "format c:"
    );

    // ==================== 超时限制 ====================

    /**
     * 全局工具执行超时上限（毫秒）
     * <p>
     * 即使调用方传入更大的 timeoutMs，也会被截断到此值。
     * 0 表示不限制（不推荐用于生产环境）。
     * </p>
     */
    @Builder.Default
    private long maxTimeoutMs = 120_000L;

    /**
     * 默认执行超时（毫秒）
     * <p>
     * 当 {@link ToolExecutionContext#getTimeoutMs()} 未设置或为 0 时使用。
     * </p>
     */
    @Builder.Default
    private long defaultTimeoutMs = 30_000L;

    // ==================== 安全校验方法 ====================

    /**
     * 校验工具执行前的安全约束
     * <p>
     * 根据工具分类检查沙箱配置是否满足要求。
     * 此方法应在 {@link BuiltinTool#execute} 被调用之前执行。
     * </p>
     *
     * @param tool    待执行的工具
     * @param context 执行上下文
     * @throws SecurityException 安全约束未满足时抛出
     */
    public void assertSandboxConstraints(BuiltinTool tool, ToolExecutionContext context) {
        if (!sandboxRequiredCategories.contains(tool.getCategory())) {
            return;
        }

        if (!context.isSandboxed()) {
            throw new SecurityException(
                    "安全策略要求 [" + tool.getCategory().getValue() + "] 类工具必须在沙箱模式下执行，"
                            + "但当前上下文 sandboxed=false。工具: " + tool.getSkillId());
        }

        if (context.getSandboxRoot() == null) {
            throw new SecurityException(
                    "安全策略要求 [" + tool.getCategory().getValue() + "] 类工具必须指定沙箱根路径，"
                            + "但当前上下文 sandboxRoot=null。工具: " + tool.getSkillId());
        }
    }

    /**
     * 校验 Shell 命令是否被安全策略允许
     * <p>
     * 执行顺序：危险关键词 → 正则黑名单 → 正则白名单。
     * </p>
     *
     * @param command 待执行的 Shell 命令
     * @return 校验结果
     */
    public CommandCheckResult checkCommand(String command) {
        if (command == null || command.isBlank()) {
            return CommandCheckResult.denied("命令不能为空");
        }

        String lowerCommand = command.toLowerCase().trim();

        // 1. 危险关键词检查（子串匹配）
        for (String keyword : blockedCommandKeywords) {
            if (lowerCommand.contains(keyword.toLowerCase())) {
                log.warn("命令被危险关键词拦截: keyword='{}', command='{}'",
                        keyword, truncate(command, LOG_COMMAND_MAX_LEN));
                return CommandCheckResult.denied("匹配危险关键词: " + keyword);
            }
        }

        // 2. 正则黑名单检查
        for (Pattern pattern : deniedCommandPatterns) {
            if (pattern.matcher(command).find()) {
                log.warn("命令被正则黑名单拦截: pattern='{}', command='{}'",
                        pattern.pattern(), truncate(command, LOG_COMMAND_MAX_LEN));
                return CommandCheckResult.denied("匹配黑名单规则: " + pattern.pattern());
            }
        }

        // 3. 正则白名单检查
        if (!allowedCommandPatterns.isEmpty()) {
            boolean matchesAny = allowedCommandPatterns.stream()
                    .anyMatch(pattern -> pattern.matcher(command).find());
            if (!matchesAny) {
                log.warn("命令未匹配白名单: command='{}'", truncate(command, LOG_COMMAND_MAX_LEN));
                return CommandCheckResult.denied("命令未匹配任何白名单规则");
            }
        }

        return CommandCheckResult.permit();
    }

    /**
     * 将执行上下文中缺失的安全参数补全为策略默认值
     * <p>
     * 此方法用于将"不完整"的 ToolExecutionContext 安全地补全，
     * 使所有字段都有合理的安全默认值。调用方传入的显式值不会被覆盖。
     * </p>
     *
     * @param context 原始上下文（允许为 null）
     * @return 补全后的安全上下文
     */
    public ToolExecutionContext enrichContext(ToolExecutionContext context) {
        if (context == null) {
            context = ToolExecutionContext.builder().build();
        }

        // 补全沙箱根路径
        if (context.getSandboxRoot() == null && defaultSandboxRoot != null) {
            context.setSandboxRoot(defaultSandboxRoot);
            context.setSandboxed(true);
        }

        // 补全工作目录
        if (context.getWorkingDirectory() == null && defaultWorkingDirectory != null) {
            context.setWorkingDirectory(defaultWorkingDirectory);
        } else if (context.getWorkingDirectory() == null && context.getSandboxRoot() != null) {
            context.setWorkingDirectory(context.getSandboxRoot());
        }

        // 补全超时（取 context 值和策略上限的较小值）
        long contextTimeout = context.getTimeoutMs();
        if (contextTimeout <= 0) {
            contextTimeout = defaultTimeoutMs;
        }
        if (maxTimeoutMs > 0 && contextTimeout > maxTimeoutMs) {
            contextTimeout = maxTimeoutMs;
        }
        context.setTimeoutMs(contextTimeout);

        return context;
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建最严格的安全策略（生产环境推荐）
     * <p>
     * 所有 FILESYSTEM 和 EXECUTION 类工具强制沙箱，
     * 超时采用字段默认值（上限 120 秒 / 默认 30 秒），
     * 危险命令关键词为默认列表。
     * </p>
     *
     * @param sandboxRoot 沙箱根路径
     * @return 严格安全策略
     */
    public static ToolSecurityPolicy strict(Path sandboxRoot) {
        return ToolSecurityPolicy.builder()
                .defaultSandboxRoot(sandboxRoot)
                .defaultWorkingDirectory(sandboxRoot)
                .build();
    }

    /**
     * 创建宽松的安全策略（开发/测试环境）
     * <p>
     * 不强制沙箱（sandboxRequiredCategories 为空），
     * 超时上限放宽到 300 秒，保留危险命令关键词拦截。
     * </p>
     *
     * @return 宽松安全策略
     */
    public static ToolSecurityPolicy permissive() {
        return ToolSecurityPolicy.builder()
                .sandboxRequiredCategories(EnumSet.noneOf(ToolCategory.class))
                .maxTimeoutMs(PERMISSIVE_MAX_TIMEOUT_MS)
                .defaultTimeoutMs(PERMISSIVE_DEFAULT_TIMEOUT_MS)
                .build();
    }

    /** 截断文本用于日志输出（防超长命令撑爆日志行）。 */
    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    // ==================== 命令检查结果 ====================

    /**
     * 命令安全检查结果
     */
    public record CommandCheckResult(boolean allowed, String reason) {

        /**
         * 允许执行
         */
        public static CommandCheckResult permit() {
            return new CommandCheckResult(true, null);
        }

        /**
         * 拒绝执行
         *
         * @param reason 拒绝原因
         */
        public static CommandCheckResult denied(String reason) {
            return new CommandCheckResult(false, reason);
        }
    }
}
