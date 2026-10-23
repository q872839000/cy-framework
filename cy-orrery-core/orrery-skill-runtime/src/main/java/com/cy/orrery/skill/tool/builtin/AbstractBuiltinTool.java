// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.tool.builtin;

import com.cy.orrery.protocol.enums.SideEffectLevel;
import com.cy.orrery.skill.tool.BuiltinTool;
import com.cy.orrery.skill.tool.ToolCategory;
import com.cy.orrery.skill.tool.ToolExecutionContext;
import com.cy.orrery.skill.tool.security.ToolSecurityPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 内置工具抽象基类
 * <p>
 * 提供通用的工具元数据字段和安全校验方法（沙箱路径断言、工作目录解析），
 * 具体工具继承此类后只需实现 {@link #doExecute(Map, ToolExecutionContext)}。
 * </p>
 * <p>
 * <b>安全机制（Defense in Depth）：</b>
 * {@link #execute(Map, ToolExecutionContext)} 方法在委托给子类 {@code doExecute} 前，
 * 会通过 {@link ToolSecurityPolicy} 执行以下安全前置校验：
 * <ul>
 *   <li>沙箱约束校验：FILESYSTEM / EXECUTION 类工具必须在沙箱模式下执行</li>
 *   <li>上下文补全：使用安全策略的默认值填充缺失的 sandboxRoot / workingDirectory / timeout</li>
 *   <li>ownerOnly 校验：非 owner 身份无法调用 ownerOnly 工具</li>
 * </ul>
 * 安全策略可通过 {@link #setSecurityPolicy(ToolSecurityPolicy)} 注入，
 * 未注入时回退到基本的参数非空校验。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
public abstract class AbstractBuiltinTool implements BuiltinTool {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final String toolId;
    private final String name;
    private final String summary;
    private final ToolCategory category;
    private final SideEffectLevel sideEffectLevel;

    /**
     * 平台级安全策略（由 BuiltinToolExecutor 或配置类注入）
     * <p>
     * 非 final，支持运行时注入。未注入时为 null，execute 方法会回退到基本校验。
     * </p>
     */
    private ToolSecurityPolicy securityPolicy;

    /**
     * 构造内置工具（副作用级别默认为 NONE）
     * <p>
     * 适用于只读工具（如 file_read、memory_search、web_fetch）。
     * </p>
     */
    protected AbstractBuiltinTool(String toolId, String name, String summary, ToolCategory category) {
        this(toolId, name, summary, category, SideEffectLevel.NONE);
    }

    /**
     * 构造内置工具（显式指定副作用级别）
     * <p>
     * 对齐 MCP 协议的 tool annotations，副作用级别影响：
     * <ul>
     *   <li>{@link com.cy.orrery.skill.tool.ToolPolicy} 的可见性过滤</li>
     *   <li>LLM function-calling 描述中的副作用提示</li>
     *   <li>未来的用户确认审批机制（Tool Approval）</li>
     * </ul>
     * </p>
     */
    protected AbstractBuiltinTool(String toolId, String name, String summary,
                                  ToolCategory category, SideEffectLevel sideEffectLevel) {
        this.toolId = toolId;
        this.name = name;
        this.summary = summary;
        this.category = category;
        this.sideEffectLevel = sideEffectLevel != null ? sideEffectLevel : SideEffectLevel.NONE;
    }

    @Override
    public String getSkillId() {
        return toolId;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getSummary() {
        return summary;
    }

    @Override
    public ToolCategory getCategory() {
        return category;
    }

    @Override
    public SideEffectLevel getSideEffectLevel() {
        return sideEffectLevel;
    }

    /**
     * 设置安全策略
     * <p>
     * 由 BuiltinToolExecutor 或自动配置类在工具注册后统一注入，
     * 确保所有工具共享同一套平台安全策略。
     * </p>
     *
     * @param securityPolicy 安全策略实例
     */
    public void setSecurityPolicy(ToolSecurityPolicy securityPolicy) {
        this.securityPolicy = securityPolicy;
    }

    /**
     * 获取当前安全策略
     *
     * @return 安全策略，未注入时返回 null
     */
    public ToolSecurityPolicy getSecurityPolicy() {
        return securityPolicy;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ToolExecutionContext context) {
        ToolExecutionContext effectiveContext = context != null ? context : ToolExecutionContext.builder().build();

        // 安全前置校验
        if (securityPolicy != null) {
            // 1. 使用安全策略补全上下文中缺失的安全参数
            effectiveContext = securityPolicy.enrichContext(effectiveContext);

            // 2. 校验沙箱约束（FILESYSTEM / EXECUTION 类工具必须在沙箱内）
            securityPolicy.assertSandboxConstraints(this, effectiveContext);
        }

        // 3. ownerOnly 工具的访问权限校验
        if (isOwnerOnly() && !effectiveContext.isOwnerAccess()) {
            throw new SecurityException(
                    "工具 '" + toolId + "' 仅允许 owner 身份调用，当前上下文 ownerAccess=false");
        }

        return doExecute(input, effectiveContext);
    }

    /**
     * 子类实现的具体执行逻辑
     * <p>
     * 此方法被调用时，安全策略校验（沙箱、ownerOnly、上下文补全）已在
     * {@link #execute(Map, ToolExecutionContext)} 中完成。
     * 子类只需关注业务逻辑实现。
     * </p>
     *
     * @param input   输入参数
     * @param context 已经过安全补全的执行上下文
     * @return 输出结果
     */
    protected abstract Map<String, Object> doExecute(Map<String, Object> input, ToolExecutionContext context);

    // ========== 通用辅助方法 ==========

    /**
     * 从输入参数中读取字符串参数
     *
     * @param input    输入参数
     * @param key      参数名
     * @param required 是否必填
     * @return 参数值
     */
    protected String readStringParam(Map<String, Object> input, String key, boolean required) {
        Object value = input.get(key);
        if (value == null || value.toString().trim().isEmpty()) {
            if (required) {
                throw new IllegalArgumentException("参数 '" + key + "' 不能为空");
            }
            return null;
        }
        return value.toString().trim();
    }

    /**
     * 从输入参数中读取整数参数
     *
     * @param input        输入参数
     * @param key          参数名
     * @param defaultValue 默认值
     * @return 参数值
     */
    protected int readIntParam(Map<String, Object> input, String key, int defaultValue) {
        Object value = input.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 从输入参数中读取布尔参数
     *
     * @param input        输入参数
     * @param key          参数名
     * @param defaultValue 默认值
     * @return 参数值
     */
    protected boolean readBoolParam(Map<String, Object> input, String key, boolean defaultValue) {
        Object value = input.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString().trim());
    }

    /**
     * 解析并校验文件路径（沙箱模式下断言路径在沙箱根目录内）
     *
     * @param filePath 用户传入的文件路径
     * @param context  执行上下文
     * @return 解析后的绝对路径
     */
    protected Path resolveAndAssertPath(String filePath, ToolExecutionContext context) {
        Path resolved;
        Path base = context.getWorkingDirectory();
        if (base != null) {
            resolved = base.resolve(filePath).normalize();
        } else {
            resolved = Path.of(filePath).toAbsolutePath().normalize();
        }

        if (context.isSandboxed() && context.getSandboxRoot() != null) {
            Path sandboxRoot = context.getSandboxRoot().normalize();
            if (!resolved.startsWith(sandboxRoot)) {
                // 异常消息会经工具错误回执进入 LLM 上下文并可能透传给用户，
                // 只回显调用方输入，不携带沙箱根等服务器本地绝对路径
                throw new SecurityException(
                        "路径越界：'" + filePath + "' 不在沙箱允许的工作目录内");
            }
            // 检查符号链接：真实路径必须也在沙箱内，防止 symlink 逃逸
            try {
                Path realPath = resolved.toRealPath();
                if (!realPath.startsWith(sandboxRoot)) {
                    throw new SecurityException(
                            "符号链接逃逸：'" + filePath + "' 的真实路径不在沙箱允许的工作目录内");
                }
            } catch (IOException e) {
                // 文件不存在时 toRealPath() 会抛异常，
                // 对于写入场景允许不存在的目标路径（normalize 已做前缀检查）
                log.debug("路径真实性检查跳过（文件可能不存在）: {}", filePath);
            }
        }
        return resolved;
    }

    /**
     * 构建成功输出 Map
     */
    protected Map<String, Object> successOutput(String key, Object value) {
        return Map.of("status", "success", key, value);
    }

    /**
     * 构建失败输出 Map
     */
    protected Map<String, Object> failureOutput(String errorMessage) {
        return Map.of("status", "failed", "error", errorMessage);
    }
}
