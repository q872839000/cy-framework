// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词技能注册表
 * <p>
 * 管理所有已加载的 {@link PromptSkill} 实例，支持按名称注册、查询和去重。
 * 线程安全，支持运行时动态注册（如热加载新安装的 Skill）。
 * </p>
 * <p>
 * <b>Skill 覆盖优先级（对齐 OpenClaw）：</b>
 * 同名 Skill 后注册的覆盖先注册的。加载顺序决定优先级：
 * <pre>
 * bundled（最低）→ installed → managed → workspace（最高）
 * </pre>
 * </p>
 * <p>
 * <b>prompt 注入过滤：</b>
 * {@link #listForPromptInjection()} 仅返回 {@code disableModelInvocation=false} 的 Skill，
 * 确保只有允许 LLM 自动调用的 Skill 被注入到 system prompt。
 * </p>
 *
 * @author pcy
 * @date 2026/03/21
 */
public class PromptSkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(PromptSkillRegistry.class);

    /**
     * 已注册的 PromptSkill（name → PromptSkill）
     * <p>
     * 使用 name 作为 key，同名 Skill 后注册覆盖先注册（优先级机制）。
     * </p>
     */
    private final Map<String, PromptSkill> skills = new ConcurrentHashMap<>();

    /**
     * 注册 PromptSkill
     * <p>
     * 同名 Skill 会被覆盖（后注册的优先级更高）。
     * </p>
     *
     * @param skill PromptSkill 实例
     */
    public void register(PromptSkill skill) {
        if (skill == null || skill.getName() == null || skill.getName().isBlank()) {
            log.warn("PromptSkill 注册失败: name 为空");
            return;
        }
        PromptSkill previous = skills.put(skill.getName(), skill);
        if (previous != null) {
            log.info("PromptSkill 覆盖注册: name={}, newSource={}, previousSource={}",
                    skill.getName(), skill.getSource(), previous.getSource());
        } else {
            log.info("PromptSkill 注册: name={}, source={}, descLength={}",
                    skill.getName(), skill.getSource(),
                    skill.getPromptContent() != null ? skill.getPromptContent().length() : 0);
        }
    }

    /**
     * 批量注册 PromptSkill
     *
     * @param skillList PromptSkill 列表
     * @return 实际注册的数量
     */
    public int registerAll(List<PromptSkill> skillList) {
        if (skillList == null || skillList.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (PromptSkill skill : skillList) {
            register(skill);
            count++;
        }
        return count;
    }

    /**
     * 注销 PromptSkill
     *
     * @param name Skill 名称
     */
    public void unregister(String name) {
        PromptSkill removed = skills.remove(name);
        if (removed != null) {
            log.info("PromptSkill 注销: name={}", name);
        }
    }

    /**
     * 获取指定名称的 PromptSkill
     *
     * @param name Skill 名称
     * @return PromptSkill 实例，不存在返回 null
     */
    public PromptSkill get(String name) {
        return skills.get(name);
    }

    /**
     * 获取所有已注册的 PromptSkill
     *
     * @return PromptSkill 列表（按名称字母序排列）
     */
    public List<PromptSkill> listAll() {
        List<PromptSkill> list = new ArrayList<>(skills.values());
        list.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return list;
    }

    /**
     * 获取应注入到 LLM system prompt 的 PromptSkill 列表
     * <p>
     * 过滤掉 {@code disableModelInvocation=true} 的 Skill。
     * 按名称字母序排列，确保 prompt 输出稳定。
     * </p>
     *
     * @return 可注入 prompt 的 PromptSkill 列表
     */
    public List<PromptSkill> listForPromptInjection() {
        return skills.values().stream()
                .filter(PromptSkill::isEligibleForPromptInjection)
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();
    }

    /**
     * 获取已注册的 PromptSkill 总数
     *
     * @return 数量
     */
    public int size() {
        return skills.size();
    }

    /**
     * 检查指定名称的 PromptSkill 是否已注册
     *
     * @param name Skill 名称
     * @return 是否已注册
     */
    public boolean contains(String name) {
        return skills.containsKey(name);
    }

    /**
     * 清空所有已注册的 PromptSkill
     */
    public void clear() {
        skills.clear();
        log.info("PromptSkillRegistry 已清空");
    }

    /**
     * 热重载：清空并重新注册所有 Skill
     * <p>
     * 用于对话安装新 Skill 后无需重启即可生效。
     * 调用方先通过 {@link com.cy.orrery.skill.prompt.PromptSkillLoader} 重新扫描目录，
     * 然后调用此方法替换全部注册。
     * </p>
     *
     * @param newSkills 新的 Skill 列表
     * @return 注册的 Skill 数量
     */
    public int reload(List<PromptSkill> newSkills) {
        skills.clear();
        int count = registerAll(newSkills);
        log.info("PromptSkillRegistry 热重载完成: count={}", count);
        return count;
    }
}
