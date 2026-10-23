package com.cy.ai.controller;

import com.cy.ai.config.AiProviderRegistry;
import com.cy.ai.vo.AiCatalogVO;
import com.cy.core.base.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 模型目录接口
 * <p>
 * 向前端模型选择器（管家对话页、知识库设置页等）提供当前服务可用的
 * provider 及能力清单。注册表热更新后，新增 provider 即时出现在目录中。
 * </p>
 *
 * @author pcy
 * @since 2026/10/02
 */
@RestController
@RequestMapping("/ai/catalog")
@Tag(name = "AI 模型目录", description = "当前服务可用的大模型 Provider 目录（前端模型选择器数据源）")
public class AiCatalogController {

    /** Provider 动态注册表 */
    private final AiProviderRegistry registry;

    public AiCatalogController(AiProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * 查询可用模型目录
     */
    @GetMapping
    @Operation(summary = "查询可用模型目录", description = "返回全部可用 provider 及其能力（chat/embedding/rerank）与默认模型")
    public R<AiCatalogVO> catalog() {
        return R.ok(AiCatalogVO.from(registry.snapshot()));
    }
}
