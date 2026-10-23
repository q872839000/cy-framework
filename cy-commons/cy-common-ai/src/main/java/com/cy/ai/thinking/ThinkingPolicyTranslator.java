package com.cy.ai.thinking;

import com.cy.ai.model.ChatCompletionRequest;
import java.util.Locale;
import java.util.Map;

/**
 * 思考策略翻译器：将 {@link ThinkingPolicy} + {@link ThinkingControlMode} 翻译为具体请求参数。
 * <p>
 * 纯函数式工具类，无状态、无依赖，可静态调用也可做 Spring Bean。
 * 翻译规则（2026-10 并轨后）：
 * </p>
 * <ul>
 *   <li>INHERIT → 不修改请求（跟随厂商默认）</li>
 *   <li>DISABLED + STANDARD / DEEPSEEK(废弃并轨) → {@code reasoning_effort=none}</li>
 *   <li>DISABLED + GLM → {@code thinking: {"type":"disabled"}}（智谱直连 4.5~4.7）</li>
 *   <li>DISABLED + QWEN → {@code enable_thinking=false}（DashScope / vLLM 直连）</li>
 *   <li>DISABLED + NONE → no-op</li>
 * </ul>
 *
 * @author pcy
 */
public final class ThinkingPolicyTranslator {

    /**
     * STANDARD / DEEPSEEK 模式下 DISABLED 对应的 reasoning_effort 值。
     * none 为 DeepSeek 官方与国内主流网关的共识扩展（OpenAI 官方端点无此取值）。
     */
    private static final String REASONING_EFFORT_DISABLED = "none";

    /** GLM 对象格式下 disabled 的 type 值。 */
    private static final String THINKING_TYPE_DISABLED = "disabled";

    /** GLM 对象格式下 enabled 的 type 值。 */
    private static final String THINKING_TYPE_ENABLED = "enabled";

    private ThinkingPolicyTranslator() {
    }

    /**
     * 将思考策略翻译为请求参数并原地写入请求。
     *
     * @param policy  思考策略
     * @param mode    厂商思考控制模式
     * @param request 聊天请求（原地修改）
     */
    public static void apply(ThinkingPolicy policy, ThinkingControlMode mode,
                             ChatCompletionRequest request) {
        if (request == null || policy == null || policy == ThinkingPolicy.INHERIT) {
            return;
        }
        if (mode == null || mode == ThinkingControlMode.NONE) {
            return;
        }
        switch (mode) {
            // DeepSeek V3.2+ 官方即 reasoning_effort（none/low/high/max），与标准通道并轨
            case STANDARD_REASONING_EFFORT, DEEPSEEK_THINKING_TYPE ->
                    request.setReasoningEffort(REASONING_EFFORT_DISABLED);
            case GLM_THINKING_TYPE ->
                    request.extra("thinking", Map.of("type", THINKING_TYPE_DISABLED));
            case QWEN_ENABLE_THINKING -> request.extra("enable_thinking", false);
            default -> { /* no-op */ }
        }
    }

    /**
     * 将请求级思考偏好档位翻译为请求参数并原地写入请求（页面思考控制的翻译入口）。
     * <p>
     * 档位语义（与 {@code ChatCompletionRequest.thinkingLevel} 契约一致）：
     * </p>
     * <ul>
     *   <li>{@code off} → 与 {@link ThinkingPolicy#DISABLED} 同路径（关思考）；</li>
     *   <li>{@code on} / {@code low} / {@code medium} / {@code high} → 开思考：
     *       STANDARD / DEEPSEEK 映射为 {@code reasoning_effort} 档位（{@code on} 归一为 {@code medium}）；
     *       GLM / QWEN 二元开关厂商无法表达强度，一律翻译为开启参数；</li>
     *   <li>{@code auto} / null / 空白 → 不修改请求（跟随 provider 全局配置）；</li>
     *   <li>mode 为 NONE → no-op（provider 未声明思考控制协议，视为模型不支持页面控制）。</li>
     * </ul>
     *
     * @param level   思考偏好档位（大小写不敏感）
     * @param mode    厂商思考控制模式
     * @param request 聊天请求（原地修改）
     */
    public static void applyLevel(String level, ThinkingControlMode mode,
                                  ChatCompletionRequest request) {
        if (request == null || level == null || level.isBlank()) {
            return;
        }
        String normalized = level.trim().toLowerCase(Locale.ROOT);
        if ("auto".equals(normalized)) {
            return;
        }
        if ("off".equals(normalized)) {
            apply(ThinkingPolicy.DISABLED, mode, request);
            return;
        }
        if (mode == null || mode == ThinkingControlMode.NONE) {
            return;
        }
        switch (mode) {
            case STANDARD_REASONING_EFFORT, DEEPSEEK_THINKING_TYPE -> {
                // 标准字段下发前清掉 extras 里的同义键，避免 @JsonAnyGetter 平铺出重复 JSON 键
                if (request.getExtraParams() != null) {
                    request.getExtraParams().remove("reasoning_effort");
                }
                request.setReasoningEffort(switch (normalized) {
                    case "low" -> "low";
                    case "high" -> "high";
                    default -> "medium";
                });
            }
            case GLM_THINKING_TYPE ->
                    request.extra("thinking", Map.of("type", THINKING_TYPE_ENABLED));
            case QWEN_ENABLE_THINKING -> request.extra("enable_thinking", true);
            default -> { /* no-op */ }
        }
    }
}
