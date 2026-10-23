package com.cy.ai.thinking;

import com.cy.ai.model.ChatCompletionRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ThinkingPolicyTranslator} 单元测试：验证各厂商思考控制模式对 DISABLED 的 wire 翻译，
 * 以及 INHERIT / NONE / null 的 no-op 语义。
 *
 * @author pcy
 */
class ThinkingPolicyTranslatorTest {

    /** DISABLED + DEEPSEEK_THINKING_TYPE（废弃并轨）→ reasoning_effort=none（与标准通道同 wire）。 */
    @Test
    void disabledWithDeepseekInjectsReasoningEffortNone() {
        ChatCompletionRequest request = new ChatCompletionRequest();

        ThinkingPolicyTranslator.apply(ThinkingPolicy.DISABLED,
                ThinkingControlMode.DEEPSEEK_THINKING_TYPE, request);

        assertEquals("none", request.getReasoningEffort());
        // 不应再携带厂商私有参数（DeepSeek V3.2+ 官方即 reasoning_effort）
        assertTrue(request.getExtraParams().isEmpty());
    }

    /** DISABLED + GLM_THINKING_TYPE → thinking: {"type":"disabled"}（智谱直连 4.5~4.7 例外通道）。 */
    @Test
    void disabledWithGlmInjectsThinkingTypeDisabled() {
        ChatCompletionRequest request = new ChatCompletionRequest();

        ThinkingPolicyTranslator.apply(ThinkingPolicy.DISABLED,
                ThinkingControlMode.GLM_THINKING_TYPE, request);

        assertEquals(Map.of("type", "disabled"), request.getExtraParams().get("thinking"));
        assertNull(request.getReasoningEffort());
    }

    /** DISABLED + QWEN_ENABLE_THINKING → enable_thinking=false（DashScope / vLLM 直连）。 */
    @Test
    void disabledWithQwenInjectsEnableThinkingFalse() {
        ChatCompletionRequest request = new ChatCompletionRequest();

        ThinkingPolicyTranslator.apply(ThinkingPolicy.DISABLED,
                ThinkingControlMode.QWEN_ENABLE_THINKING, request);

        Map<String, Object> extras = request.getExtraParams();
        assertEquals(1, extras.size());
        assertEquals(Boolean.FALSE, extras.get("enable_thinking"));
        assertNull(request.getReasoningEffort());
    }

    /** DISABLED + STANDARD_REASONING_EFFORT → reasoning_effort=none（国产厂商共识扩展值）。 */
    @Test
    void disabledWithStandardInjectsReasoningEffortNone() {
        ChatCompletionRequest request = new ChatCompletionRequest();

        ThinkingPolicyTranslator.apply(ThinkingPolicy.DISABLED,
                ThinkingControlMode.STANDARD_REASONING_EFFORT, request);

        assertEquals("none", request.getReasoningEffort());
        assertTrue(request.getExtraParams().isEmpty());
    }

    /** INHERIT 策略不修改请求，即使 provider 声明了控制模式。 */
    @Test
    void inheritIsNoOpEvenWithMode() {
        ChatCompletionRequest request = new ChatCompletionRequest();

        ThinkingPolicyTranslator.apply(ThinkingPolicy.INHERIT,
                ThinkingControlMode.DEEPSEEK_THINKING_TYPE, request);

        assertTrue(request.getExtraParams().isEmpty());
        assertNull(request.getReasoningEffort());
    }

    /** NONE / null 模式为 no-op（无思考控制能力的 provider）。 */
    @Test
    void noneOrNullModeIsNoOp() {
        ChatCompletionRequest request = new ChatCompletionRequest();

        ThinkingPolicyTranslator.apply(ThinkingPolicy.DISABLED, ThinkingControlMode.NONE, request);
        ThinkingPolicyTranslator.apply(ThinkingPolicy.DISABLED, null, request);

        assertTrue(request.getExtraParams().isEmpty());
        assertNull(request.getReasoningEffort());
    }

    /** null 请求不抛异常（防御式契约）。 */
    @Test
    void nullRequestIsSafe() {
        ThinkingPolicyTranslator.apply(ThinkingPolicy.DISABLED,
                ThinkingControlMode.DEEPSEEK_THINKING_TYPE, null);
    }

    /** applyLevel=off 与 DISABLED 同路径（GLM wire 格式验证）。 */
    @Test
    void levelOffFollowsDisabledPath() {
        ChatCompletionRequest request = new ChatCompletionRequest();

        ThinkingPolicyTranslator.applyLevel("off", ThinkingControlMode.GLM_THINKING_TYPE, request);

        assertEquals(Map.of("type", "disabled"), request.getExtraParams().get("thinking"));
    }

    /** applyLevel 强度档位 + 标准模式 → reasoning_effort 对应档位，且清掉 extras 同义键。 */
    @Test
    void levelEffortWithStandardSetsReasoningEffort() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.extra("reasoning_effort", "high");

        ThinkingPolicyTranslator.applyLevel("low", ThinkingControlMode.STANDARD_REASONING_EFFORT, request);

        assertEquals("low", request.getReasoningEffort());
        assertFalse(request.getExtraParams().containsKey("reasoning_effort"));
    }

    /** applyLevel=on + 标准模式归一为 medium。 */
    @Test
    void levelOnWithStandardNormalizesToMedium() {
        ChatCompletionRequest request = new ChatCompletionRequest();

        ThinkingPolicyTranslator.applyLevel("on", ThinkingControlMode.STANDARD_REASONING_EFFORT, request);

        assertEquals("medium", request.getReasoningEffort());
    }

    /** applyLevel 强度档位 + 二元厂商（强度无法表达）→ 翻译为开启参数；并轨模式按标准通道发档位。 */
    @Test
    void levelEffortWithBinaryVendorTranslatesToEnabled() {
        ChatCompletionRequest glm = new ChatCompletionRequest();
        ChatCompletionRequest qwen = new ChatCompletionRequest();
        ChatCompletionRequest deepseek = new ChatCompletionRequest();

        ThinkingPolicyTranslator.applyLevel("high", ThinkingControlMode.GLM_THINKING_TYPE, glm);
        ThinkingPolicyTranslator.applyLevel("low", ThinkingControlMode.QWEN_ENABLE_THINKING, qwen);
        ThinkingPolicyTranslator.applyLevel("low", ThinkingControlMode.DEEPSEEK_THINKING_TYPE, deepseek);

        assertEquals(Map.of("type", "enabled"), glm.getExtraParams().get("thinking"));
        assertEquals(Boolean.TRUE, qwen.getExtraParams().get("enable_thinking"));
        assertEquals("low", deepseek.getReasoningEffort());
    }

    /** applyLevel 为 auto / 空白 / NONE 模式时 no-op（跟随 provider 全局配置）。 */
    @Test
    void levelAutoBlankOrNoneModeIsNoOp() {
        ChatCompletionRequest request = new ChatCompletionRequest();

        ThinkingPolicyTranslator.applyLevel("auto", ThinkingControlMode.GLM_THINKING_TYPE, request);
        ThinkingPolicyTranslator.applyLevel("  ", ThinkingControlMode.GLM_THINKING_TYPE, request);
        ThinkingPolicyTranslator.applyLevel(null, ThinkingControlMode.GLM_THINKING_TYPE, request);
        ThinkingPolicyTranslator.applyLevel("on", ThinkingControlMode.NONE, request);

        assertTrue(request.getExtraParams().isEmpty());
        assertNull(request.getReasoningEffort());
    }
}
