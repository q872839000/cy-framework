// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.constant;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Agent 运行时业务错误码枚举（字符串 code 体系）。
 * <p>
 * 与 CYException 数字码体系（error-messages-orrery-agent-runtime.properties，50500-50599 段）
 * 平行存在：数字码面向宿主 HTTP 响应 / 程序异常；本枚举面向引擎结果的
 * failedCode 语义（宿主经事件通道下发给前端 / 调用方）。两套体系勿合并。
 * </p>
 * <p>
 * 消息模板 i18n：{@link #format} 优先取资源束 {@code runtime-error-codes}
 * （按 JVM 默认 Locale 解析 {@code _en} 等变体；基文件为中文），缺束 / 缺键
 * 回落枚举内置模板——资源束由宿主按需放置，不引入硬依赖。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public enum RuntimeErrorCode {

    /** ReAct 循环连续多轮工具失败且零业务进展，退化熔断中止。 */
    REPEATED_TOOL_FAILURE("REPEATED_TOOL_FAILURE",
            "本轮操作连续多轮失败、没有进展，已停止以免空耗。", false),

    /** LLM 调用失败（适配器未提供更细错误码时的兜底）。 */
    LLM_CALL_FAILED("LLM_CALL_FAILED", "模型调用失败：{0}", true),

    /** LLM 重试退避等待期间线程被中断，按取消语义处理。 */
    LLM_CANCELLED("LLM_CANCELLED", "模型调用重试等待期间被中断，本轮按取消处理。", false),

    /** 模型输出被截断（达到 max_tokens 且无续接装饰器 / 续接失败），半截结果不可信。 */
    AGENT_OUTPUT_TRUNCATED("AGENT_OUTPUT_TRUNCATED",
            "模型输出被截断（达到长度上限），结果不完整，请缩小输出规模后重试。", true),

    /** 输出截断后自动续接失败或达到续接轮数上限。 */
    TRUNCATION_CONTINUATION_EXCEEDED("TRUNCATION_CONTINUATION_EXCEEDED",
            "模型输出被截断且自动续接失败（或达续接上限），请缩小输出规模后重试。", true),

    /** LLM tool_call 参数不是合法 JSON，工具未执行（失败回执反馈模型自纠）。 */
    TOOL_ARGS_INVALID("TOOL_ARGS_INVALID",
            "工具 {0} 的参数不是合法 JSON，未执行；请修正参数格式后重试。", true),

    /** 模型输出被内容安全过滤拦截（确定性结局，重试无意义）。 */
    CONTENT_FILTERED("CONTENT_FILTERED", "模型输出被内容安全过滤拦截，请调整输入后重试。", false),

    /** 阶段链处理器缺失（阶段未注册处理器）。 */
    PIPELINE_STAGE_MISSING("PIPELINE_STAGE_MISSING", "阶段处理器缺失：{0}", false),

    /** 阶段链转移次数超过上限，熔断终止。 */
    PIPELINE_TRANSITIONS_EXCEEDED("PIPELINE_TRANSITIONS_EXCEEDED",
            "阶段链转移次数超过上限（{0}），已熔断终止。", true),

    /** 阶段回退次数超过预算，熔断终止。 */
    PIPELINE_ROLLBACKS_EXCEEDED("PIPELINE_ROLLBACKS_EXCEEDED",
            "阶段回退次数超过预算（{0}），已熔断终止。", true),

    /** 工具执行超时（强取消后返回，LLM 可调整参数重试）。 */
    TOOL_TIMEOUT("TOOL_TIMEOUT", "工具执行超时（{0} ms），已强制取消。", true),

    /** 相同参数指纹连续失败达到阈值，硬熔断拒绝执行。 */
    TOOL_ARG_FUSE("TOOL_ARG_FUSE",
            "相同参数已连续失败 {0} 次，请调整参数后重试，不要原样重复。", false),

    /** DAG 图校验失败（重复节点 ID / 缺失依赖 / 环）。 */
    DAG_GRAPH_INVALID("DAG_GRAPH_INVALID", "执行计划图非法：{0}", false),

    /** DAG 节点执行超时（强取消后按可重试失败处理）。 */
    DAG_NODE_TIMEOUT("DAG_NODE_TIMEOUT", "计划节点 {0} 执行超时（{1} ms），已强制取消。", true),

    /** DAG 节点处理器抛出未捕获异常。 */
    DAG_NODE_HANDLER_ERROR("DAG_NODE_HANDLER_ERROR",
            "计划节点 {0} 执行异常：{1}", false),

    /** DAG 节点处理器返回了非法结果类型（Dropped 属引擎内部语义）。 */
    DAG_NODE_OUTCOME_INVALID("DAG_NODE_OUTCOME_INVALID",
            "计划节点 {0} 的处理器返回了非法结果类型。", false),

    /** DAG 节点执行总次数超过预算，熔断终止。 */
    DAG_EXECUTIONS_EXCEEDED("DAG_EXECUTIONS_EXCEEDED",
            "计划节点执行次数超过预算（{0}），已熔断终止。", true);

    /** 消息模板资源束基名（i18n 覆盖面；缺束回落枚举内置模板）。 */
    private static final String RUNTIME_CODES_BUNDLE = "runtime-error-codes";

    private final String code;
    private final String messageTemplate;
    private final boolean retryable;

    RuntimeErrorCode(String code, String messageTemplate, boolean retryable) {
        this.code = code;
        this.messageTemplate = messageTemplate;
        this.retryable = retryable;
    }

    /**
     * 错误码。
     *
     * @return 字符串错误码
     */
    public String code() {
        return code;
    }

    /**
     * 是否可重试。
     *
     * @return true 表示可重试
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * 按模板格式化错误消息（模板经资源束按 Locale 解析，缺束回落内置中文模板）。
     *
     * @param args 模板参数
     * @return 格式化后的消息
     */
    public String format(Object... args) {
        String template = messageTemplate;
        try {
            template = ResourceBundle.getBundle(RUNTIME_CODES_BUNDLE).getString(code);
        } catch (MissingResourceException e) {
            // 宿主未放置资源束 / 键缺失：回落枚举内置模板（组件独立可用）
        }
        MessageFormat mf = new MessageFormat(template, Locale.ROOT);
        return mf.format(args == null ? new Object[0] : args);
    }
}
