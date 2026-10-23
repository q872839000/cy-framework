package com.cy.orrery.skill.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClasspathSkillLoader} 契约测试：classpath 扫描、目录分组、
 * frontmatter 解析委托、L3 资源收集、损坏目录跳过。
 *
 * @author pcy
 * @date 2026/08/14
 */
class ClasspathSkillLoaderTest {

    private final ClasspathSkillLoader loader =
            new ClasspathSkillLoader("classpath*:test-skills/**/*.md");

    @Test
    void loadsSkillsGroupedByDirectory() {
        List<PromptSkill> skills = loader.load("bundled");

        // broken-skill 目录缺少 SKILL.md 被跳过，仅加载 alpha / beta
        assertEquals(2, skills.size());
        assertEquals(List.of("alpha-skill", "beta-skill"),
                skills.stream().map(PromptSkill::getName).sorted().toList());
    }

    @Test
    void parsesFrontmatterAndCollectsResources() {
        PromptSkill alpha = loader.load("bundled").stream()
                .filter(s -> "alpha-skill".equals(s.getName()))
                .findFirst().orElseThrow();

        assertEquals("测试技能甲——验证 classpath 加载与 L3 资源收集", alpha.getDescription());
        assertEquals("bundled", alpha.getSource());
        assertTrue(alpha.getPromptContent().contains("{{include:extra.md}}"));
        // L3 附属资源：文件名 → 内容
        assertEquals(1, alpha.getResources().size());
        assertTrue(alpha.getResources().get("extra.md").contains("附属资源正文"));
        // frontmatter 原始数据保留（消费方私有扩展字段经此读取）
        assertNotNull(alpha.getFrontmatter().get("metadata"));
    }

    @Test
    void fallsBackToDirectoryNameWhenNameMissing() {
        PromptSkill beta = loader.load("bundled").stream()
                .filter(s -> "beta-skill".equals(s.getName()))
                .findFirst().orElseThrow();

        assertEquals("测试技能乙——无 name 字段，应回退为目录名", beta.getDescription());
        assertTrue(beta.getResources().isEmpty());
    }

    @Test
    void returnsEmptyWhenPatternMatchesNothing() {
        ClasspathSkillLoader empty = new ClasspathSkillLoader("classpath*:no-such-dir/**/*.md");
        assertTrue(empty.load("bundled").isEmpty());
    }
}
