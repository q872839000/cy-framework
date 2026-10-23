package com.cy.ai.vo;

import com.cy.ai.config.AiProviderRegistry;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 模型目录视图
 * <p>
 * 前端模型选择器的数据源：当前服务全部可用 provider 及其能力（chat / embedding / rerank）
 * 与各能力默认模型。能力以对应模型字段非空表达（null = 不具备该能力）。
 * 永不包含 api-key 等敏感信息。
 * </p>
 *
 * @author pcy
 * @since 2026/10/02
 */
@Data
@Builder
public class AiCatalogVO {

    /** 默认 provider 名称 */
    private String defaultProvider;

    /** 可用 provider 列表 */
    private List<ProviderItem> providers;

    /**
     * 单个 provider 的目录项
     */
    @Data
    @Builder
    public static class ProviderItem {

        /** provider 名称（cy.ai.providers 的 key，调用方按名指定时使用） */
        private String name;

        /** 是否当前默认 provider */
        private boolean defaultProvider;

        /** Chat 默认模型（null = 未配置默认模型，调用时需显式指定） */
        private String chatModel;

        /** Chat 模型是否具备视觉（图片输入）能力（前端据此开放 / 隐藏图片输入入口） */
        private boolean chatVision;

        /** Chat 模型是否支持思考控制（provider 已声明思考控制协议；前端据此显示思考开关） */
        private boolean chatThinking;

        /** 思考控制是否支持强度档位（reasoning_effort 通道；false = 仅开 / 关二元控制） */
        private boolean chatThinkingEffort;

        /**
         * 强制思考模型子集（chatModels 中不允许关闭思考的模型，如 GLM-5.3 系列）。
         * 前端对选中该类模型的会话禁用「关闭思考」档，只保留强度档。
         */
        private java.util.List<String> chatForcedModels;

        /**
         * 可用对话模型清单（provider 下可切换的模型集合，前端分组选择用）：
         * 取 {@code chat.models} 配置；未配置时回退为仅默认模型单例
         */
        private java.util.List<String> chatModels;

        /** Embedding 模型（null = 不具备 embedding 能力） */
        private String embeddingModel;

        /** Rerank 模型（null = 不具备 rerank 能力） */
        private String rerankModel;
    }

    /**
     * 强制思考模型标记（小写包含匹配）：命中即该模型不允许关闭思考，只能调强度。
     * GLM-5.3 系列为强制思考（智谱 / Z.AI 官方声明：关闭参数会被拒），新模型按需追加。
     */
    private static final List<String> FORCED_THINKING_MARKERS = List.of("glm-5.3");

    /**
     * 判断模型是否强制思考（null / 空白安全）。
     *
     * @param model 模型名
     * @return true 表示该模型不允许关闭思考
     */
    static boolean isForcedThinkingModel(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String lower = model.toLowerCase();
        return FORCED_THINKING_MARKERS.stream().anyMatch(lower::contains);
    }

    /**
     * 从注册表快照构建目录视图
     *
     * @param snapshot 注册表当前快照
     * @return 目录视图
     */
    public static AiCatalogVO from(AiProviderRegistry.Snapshot snapshot) {
        List<ProviderItem> items = new ArrayList<>();
        snapshot.providerConfigs().forEach((name, config) -> {
            String chatModel = config.getChat() != null && config.getChat().getOptions() != null
                    ? config.getChat().getOptions().getModel() : null;
            String embeddingModel = config.getEmbedding() != null && config.getEmbedding().isEnabled()
                    && config.getEmbedding().getOptions() != null
                    ? config.getEmbedding().getOptions().getModel() : null;
            String rerankModel = config.getRerank() != null && config.getRerank().isEnabled()
                    && config.getRerank().getOptions() != null
                    ? config.getRerank().getOptions().getModel() : null;
            java.util.List<String> configuredModels = config.getChat() != null && config.getChat().getModels() != null
                    ? config.getChat().getModels().stream()
                        .filter(m -> m != null && !m.isBlank()).map(String::trim).distinct().toList()
                    : java.util.List.<String>of();
            com.cy.ai.thinking.ThinkingControlMode thinkingMode = config.getChat() != null
                    && config.getChat().getOptions() != null
                    ? config.getChat().getOptions().getThinkingControlMode() : null;
            boolean thinkingCapable = thinkingMode != null
                    && thinkingMode != com.cy.ai.thinking.ThinkingControlMode.NONE;
            // 强度档 = reasoning_effort 通道（标准模式与已并轨的 DeepSeek 废弃模式）；
            // GLM 直连对象型与 Qwen 布尔型为二元开关，无法表达强度
            boolean thinkingEffort = thinkingMode == com.cy.ai.thinking.ThinkingControlMode.STANDARD_REASONING_EFFORT
                    || thinkingMode == com.cy.ai.thinking.ThinkingControlMode.DEEPSEEK_THINKING_TYPE;
            List<String> chatModels = !configuredModels.isEmpty()
                    ? configuredModels
                    : (chatModel != null ? java.util.List.of(chatModel) : java.util.List.of());
            List<String> forcedModels = chatModels.stream()
                    .filter(AiCatalogVO::isForcedThinkingModel).toList();
            items.add(ProviderItem.builder()
                    .name(name)
                    .defaultProvider(name.equals(snapshot.defaultProvider()))
                    .chatModel(chatModel)
                    .chatVision(config.getChat() != null && config.getChat().isVisionEnabled())
                    .chatThinking(thinkingCapable)
                    .chatThinkingEffort(thinkingEffort)
                    .chatForcedModels(forcedModels)
                    .chatModels(chatModels)
                    .embeddingModel(embeddingModel)
                    .rerankModel(rerankModel)
                    .build());
        });
        return AiCatalogVO.builder()
                .defaultProvider(snapshot.defaultProvider())
                .providers(items)
                .build();
    }
}
