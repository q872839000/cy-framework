// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.tool.executor;

import com.cy.core.exception.CYException;
import com.cy.orrery.skill.tool.BuiltinTool;
import com.cy.orrery.skill.tool.ToolExecutionContext;
import com.cy.orrery.skill.tool.ToolExecutionResult;
import com.cy.orrery.skill.tool.ToolPolicy;
import com.cy.orrery.skill.tool.registry.BuiltinToolRegistry;
import com.cy.orrery.skill.tool.security.ToolSecurityPolicy;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 内置工具执行器
 * <p>
 * 接收 toolId 和输入参数，从 {@link BuiltinToolRegistry} 查找工具实例，
 * 校验 {@link ToolPolicy} 权限后执行。包含执行耗时统计、异常捕获和日志审计。
 * </p>
 * <p>
 * <b>安全增强：</b>
 * <ul>
 *   <li>执行器持有 {@link ToolSecurityPolicy}，会在工具注册时自动注入安全策略</li>
 *   <li>所有工具执行都包裹在超时保护中，防止单个工具阻塞整个编排循环</li>
 *   <li>安全策略通过 {@link ToolSecurityPolicy#enrichContext} 补全上下文缺失的安全参数</li>
 * </ul>
 * </p>
 * <p>
 * 参考 OpenClaw 的工具执行链路：查找 → 策略校验 → 安全上下文 → 超时执行 → 结果封装。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Slf4j
public class BuiltinToolExecutor {

    private final BuiltinToolRegistry registry;
    private final ToolPolicy defaultPolicy;
    private final ToolSecurityPolicy securityPolicy;

    /**
     * 超时执行线程池（虚拟线程，轻量级）
     * <p>
     * 用于将工具执行包裹在 {@code Future.get(timeout)} 中，
     * 超时后可中断执行线程并返回超时错误。
     * </p>
     */
    private final ExecutorService timeoutExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 完整构造器
     *
     * @param registry       工具注册表
     * @param defaultPolicy  默认可见性策略
     * @param securityPolicy 安全策略（null 时不强制安全约束，不推荐用于生产环境）
     */
    public BuiltinToolExecutor(BuiltinToolRegistry registry,
                               ToolPolicy defaultPolicy,
                               ToolSecurityPolicy securityPolicy) {
        this.registry = registry;
        this.defaultPolicy = defaultPolicy != null ? defaultPolicy : ToolPolicy.allowAll();
        this.securityPolicy = securityPolicy;
    }

    /**
     * 构造执行器（无安全策略——仅开发用，生产请注入 ToolSecurityPolicy）。
     *
     * @param registry      工具注册表
     * @param defaultPolicy 默认可见性策略
     */
    public BuiltinToolExecutor(BuiltinToolRegistry registry, ToolPolicy defaultPolicy) {
        this(registry, defaultPolicy, null);
    }

    /**
     * 构造执行器（无安全策略与可见性限制——仅开发用）。
     *
     * @param registry 工具注册表
     */
    public BuiltinToolExecutor(BuiltinToolRegistry registry) {
        this(registry, ToolPolicy.allowAll(), null);
    }

    /**
     * 获取安全策略
     *
     * @return 安全策略实例，未配置时返回 null
     */
    public ToolSecurityPolicy getSecurityPolicy() {
        return securityPolicy;
    }

    /**
     * 执行指定内置工具（使用默认策略和默认上下文）
     *
     * @param toolId 工具ID
     * @param input  输入参数
     * @return 执行结果
     */
    public ToolExecutionResult execute(String toolId, Map<String, Object> input) {
        return execute(toolId, input, null, null);
    }

    /**
     * 执行指定内置工具（使用指定上下文和默认策略）
     *
     * @param toolId  工具ID
     * @param input   输入参数
     * @param context 执行上下文
     * @return 执行结果
     */
    public ToolExecutionResult execute(String toolId, Map<String, Object> input, ToolExecutionContext context) {
        return execute(toolId, input, context, null);
    }

    /**
     * 执行指定内置工具（完整参数）
     * <p>
     * 执行链路：查找 → ToolPolicy 可见性校验 → 安全上下文补全 → 超时包裹执行 → 结果封装。
     * 安全策略校验（沙箱/ownerOnly）委托给 {@link com.cy.orrery.skill.tool.builtin.AbstractBuiltinTool#execute}。
     * </p>
     *
     * @param toolId  工具ID
     * @param input   输入参数
     * @param context 执行上下文（null 时使用安全策略默认上下文）
     * @param policy  工具策略（null 时使用默认策略）
     * @return 执行结果
     */
    public ToolExecutionResult execute(String toolId, Map<String, Object> input,
                                       ToolExecutionContext context, ToolPolicy policy) {
        // 1. 查找工具
        BuiltinTool tool = registry.getTool(toolId);
        if (tool == null) {
            log.warn("内置工具未找到: toolId={}", toolId);
            return ToolExecutionResult.failure(toolId, 50301, resolveMessage(50301));
        }

        // 2. ToolPolicy 可见性校验
        ToolPolicy effectivePolicy = policy != null ? policy : defaultPolicy;
        if (!effectivePolicy.isAllowed(tool)) {
            log.warn("内置工具被策略拒绝: toolId={}", toolId);
            return ToolExecutionResult.failure(toolId, 50004, resolveMessage(50004));
        }

        // 3. 构建安全上下文（安全策略补全缺失参数）
        ToolExecutionContext effectiveContext = context != null ? context : ToolExecutionContext.builder().build();
        if (securityPolicy != null) {
            effectiveContext = securityPolicy.enrichContext(effectiveContext);
        }

        // 4. 超时包裹执行
        long timeoutMs = effectiveContext.getTimeoutMs();
        log.info("开始执行内置工具: toolId={}, name={}, timeoutMs={}", toolId, tool.getName(), timeoutMs);
        long start = System.currentTimeMillis();

        ToolExecutionContext finalContext = effectiveContext;
        Callable<Map<String, Object>> task = () -> tool.execute(input, finalContext);

        try {
            Map<String, Object> output;
            if (timeoutMs > 0) {
                // 有超时限制：提交到线程池，用 Future.get(timeout) 包裹
                Future<Map<String, Object>> future = timeoutExecutor.submit(task);
                try {
                    output = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    long duration = System.currentTimeMillis() - start;
                    log.error("内置工具执行超时: toolId={}, timeoutMs={}, durationMs={}",
                            toolId, timeoutMs, duration);
                    return ToolExecutionResult.failure(toolId, 50305,
                            resolveMessage(50305), duration);
                }
            } else {
                // 无超时限制：直接执行
                output = task.call();
            }

            long duration = System.currentTimeMillis() - start;
            log.info("内置工具执行成功: toolId={}, durationMs={}", toolId, duration);
            return ToolExecutionResult.success(toolId, output, duration);
        } catch (ExecutionException e) {
            // 解包内部异常
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return handleException(toolId, cause, start);
        } catch (InterruptedException e) {
            // 等待线程被中断（运行取消）：恢复中断标记，按执行失败收敛
            Thread.currentThread().interrupt();
            long duration = System.currentTimeMillis() - start;
            log.warn("内置工具执行被中断: toolId={}", toolId);
            return ToolExecutionResult.failure(toolId, 50303, resolveMessage(50303), duration);
        } catch (SecurityException e) {
            long duration = System.currentTimeMillis() - start;
            log.error("内置工具安全校验失败: toolId={}", toolId, e);
            // 透出越界细节（如沙箱外绝对路径），让调用方/LLM 能据此自纠
            return ToolExecutionResult.failure(toolId, 50004,
                    resolveMessage(50004, e.getMessage()), duration);
        } catch (IllegalArgumentException e) {
            long duration = System.currentTimeMillis() - start;
            log.error("内置工具参数错误: toolId={}", toolId, e);
            return ToolExecutionResult.failure(toolId, 50001,
                    resolveMessage(50001), duration);
        } catch (Exception e) {
            return handleException(toolId, e, start);
        }
    }

    /**
     * 统一异常处理（按异常类型映射错误码：安全校验 50004 / 参数 50001 / 其余 50303）。
     */
    private ToolExecutionResult handleException(String toolId, Throwable cause, long startMs) {
        long duration = System.currentTimeMillis() - startMs;
        if (cause instanceof SecurityException) {
            log.error("内置工具安全校验失败: toolId={}", toolId, cause);
            return ToolExecutionResult.failure(toolId, 50004,
                    resolveMessage(50004, cause.getMessage()), duration);
        }
        if (cause instanceof IllegalArgumentException) {
            log.error("内置工具参数错误: toolId={}", toolId, cause);
            return ToolExecutionResult.failure(toolId, 50001,
                    resolveMessage(50001), duration);
        }
        log.error("内置工具执行失败: toolId={}, durationMs={}", toolId, duration, cause);
        return ToolExecutionResult.failure(toolId, 50303,
                resolveMessage(50303), duration);
    }

    /** 按错误码解析 i18n 消息。 */
    private String resolveMessage(int errorCode, Object... args) {
        return new CYException(errorCode, args).getMessage();
    }
}
