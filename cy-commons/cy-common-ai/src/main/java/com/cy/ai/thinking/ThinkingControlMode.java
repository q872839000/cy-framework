package com.cy.ai.thinking;

/**
 * 厂商思考控制模式：声明 provider 使用哪种 wire 协议控制思考开关。
 * <p>
 * 由 {@code cy.ai.providers.<id>.chat.options.thinking-control-mode} 配置注入。
 * {@link ThinkingPolicyTranslator} 据此将 {@link ThinkingPolicy} / 思考档位翻译为具体请求参数。
 * </p>
 * <p>
 * 选型指引（2026-10 查证）：
 * </p>
 * <ul>
 *   <li>OpenAI 兼容网关（SiliconFlow / 百炼兼容模式 / ModelScope 等）与 DeepSeek 官方：
 *       首选 {@link #STANDARD_REASONING_EFFORT}——网关会在内部映射到各模型的原生参数，
 *       一个参数同时覆盖开关（none）与强度（low / medium / high）；</li>
 *   <li>智谱直连（open.bigmodel.cn）：GLM-4.5~4.7 只认 thinking.type，用 {@link #GLM_THINKING_TYPE}；
 *       GLM-5.2+ 直连可用 STANDARD 调强度，但「关闭」仍需 GLM 模式的 thinking.type（智谱侧无 none）；</li>
 *   <li>Qwen / DashScope 直连与 vLLM 自部署：官方协议仍是 enable_thinking，用 {@link #QWEN_ENABLE_THINKING}。</li>
 * </ul>
 *
 * @author pcy
 */
public enum ThinkingControlMode {
    /**
     * OpenAI 规范标准通道：通过 {@code reasoning_effort} 字段控制。
     * DISABLED → {@code reasoning_effort=none}（DeepSeek 官方 / SiliconFlow 等网关均认 none 为关闭；
     * 注意 OpenAI 官方端点无 none 取值，仅有 minimal 及以上档位）。
     */
    STANDARD_REASONING_EFFORT,

    /**
     * 智谱对象格式：通过 {@code thinking: {"type":"disabled"}} 控制。
     * 仅用于智谱直连 GLM-4.5~4.7 等不支持 reasoning_effort 的模型；
     * GLM-5.3 系列为强制思考模型，任何关闭参数都会被拒。
     */
    GLM_THINKING_TYPE,

    /**
     * Qwen / DashScope 布尔格式：通过 {@code enable_thinking: false} 控制。
     * DashScope 直连与 vLLM 自部署的官方协议；经 SiliconFlow 中转时优先 STANDARD。
     */
    QWEN_ENABLE_THINKING,

    /**
     * DeepSeek 对象格式（已废弃）。
     * <p>
     * DeepSeek V3.2+ 官方 API 已原生支持 OpenAI 格式的 {@code reasoning_effort}
     * （none / low / high / max），本模式的行为已与 {@link #STANDARD_REASONING_EFFORT} 完全并轨，
     * 保留枚举值仅为兼容存量 Nacos 配置，新配置请直接使用 STANDARD。
     * </p>
     *
     * @deprecated 使用 {@link #STANDARD_REASONING_EFFORT}（行为等价）
     */
    @Deprecated
    DEEPSEEK_THINKING_TYPE,

    /** 无思考控制能力：DISABLED 为 no-op，思考始终跟随模型默认。 */
    NONE
}
