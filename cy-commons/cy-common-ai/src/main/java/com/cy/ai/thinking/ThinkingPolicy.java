package com.cy.ai.thinking;

/**
 * 思考策略：声明调用方对模型思考的意图，与厂商解耦。
 * <p>
 * 由 {@code LlmCallPurpose.Reasoning} 映射而来——FORCE_OFF→{@link #DISABLED}，
 * INHERIT→{@link #INHERIT}。策略本身不含任何厂商参数，翻译由
 * {@link ThinkingPolicyTranslator} 按 provider 的 {@link ThinkingControlMode} 完成。
 * </p>
 *
 * @author pcy
 */
public enum ThinkingPolicy {
    /** 强制关闭思考：机械型调用（意图分类、字段取值等），翻译器按厂商模式下发关闭参数。 */
    DISABLED,
    /** 继承厂商默认：不下发任何思考覆盖参数，跟随 provider 配置的默认行为。 */
    INHERIT
}
