// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.profile;

import com.cy.core.exception.CYException;
import com.cy.orrery.memory.api.support.SnowflakeIdGenerator;
import com.cy.orrery.memory.prompt.PromptResolver;
import com.cy.orrery.memory.support.FakeChatPort;
import com.cy.orrery.memory.support.InMemoryProfileStore;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画像服务测试（维度下放 + 仲裁规则 + 渲染预算）。
 *
 * @author pcy
 */
class ProfileServiceTest {

    /** 军事维度示例。 */
    private List<ProfileDimension> militaryDimensions() {
        return List.of(
                new ProfileDimension("branch", "军种", "用户所属军种", false, 1),
                new ProfileDimension("move_semantics", "「移动」指令语义偏好",
                        "用户说「移动」时的默认含义", false, 1),
                new ProfileDimension("design_habits", "体系设计行为", "惯用结构", true, 3));
    }

    /** 本测试方法内共享的发号器（与生产单 Bean 语义一致；JUnit 每方法新建测试实例）。 */
    private final SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1);

    private ProfileService newService(InMemoryProfileStore store, FakeChatPort chat) {
        // 同一测试内的多个服务实例共享一个发号器：各自建同节点发号器在同一毫秒内
        // 会产生相同雪花 ID（Windows 计时器粒度约 15ms），与生产单 Bean 装配不符
        return new ProfileService(store,
                List.of(this::militaryDimensions),
                new ProfileExtractor(chat, new PromptResolver()),
                ids, Clock.systemUTC());
    }

    @Test
    void extractAndUpdateAppliesDimensionDrivenExtraction() {
        InMemoryProfileStore store = new InMemoryProfileStore();
        FakeChatPort chat = new FakeChatPort().reply("""
                {"entries":[{"dimension":"move_semantics","value":"A) 初始部署位置调整","inferred":true,"evidence":"把3号车移动到东侧"},
                 {"dimension":"branch","value":"陆军","inferred":false,"evidence":"我是陆军"}]}
                """);
        ProfileService service = newService(store, chat);
        int saved = service.extractAndUpdate(ProfileItem.OwnerType.USER, null, "u1",
                "用户：把3号车移动到东侧\n用户：我是陆军");
        assertEquals(2, saved);
        List<ProfileItem> items = service.getItems(ProfileItem.OwnerType.USER, null, "u1",
                "move_semantics");
        assertEquals(1, items.size());
        assertTrue(items.get(0).isInferred());
    }

    @Test
    void inferredValueCannotOverrideExplicitValue() {
        InMemoryProfileStore store = new InMemoryProfileStore();
        FakeChatPort chat = new FakeChatPort().reply(
                "[{\"dimension\":\"move_semantics\",\"value\":\"A) 初始部署调整\",\"inferred\":true}]");
        ProfileService service = newService(store, chat);
        service.addExplicit(ProfileItem.OwnerType.USER, null, "u1", "move_semantics",
                "B) 机动规划", "用户显式说明");
        // 推断值试图覆盖显式值 → 仲裁拒绝
        int saved = service.extractAndUpdate(ProfileItem.OwnerType.USER, null, "u1",
                "用户：把3号车移动到东侧");
        assertEquals(0, saved);
        assertEquals("B) 机动规划", service.getItems(ProfileItem.OwnerType.USER, null, "u1",
                "move_semantics").get(0).getValue());
    }

    @Test
    void explicitValueSupersedesOldOne() {
        InMemoryProfileStore store = new InMemoryProfileStore();
        ProfileService service = newService(store, new FakeChatPort());
        service.addExplicit(ProfileItem.OwnerType.USER, null, "u1", "branch", "陆军", null);
        service.addExplicit(ProfileItem.OwnerType.USER, null, "u1", "branch", "海军", null);
        List<ProfileItem> active = service.getItems(ProfileItem.OwnerType.USER, null, "u1", "branch");
        assertEquals(1, active.size());
        assertEquals("海军", active.get(0).getValue());
    }

    @Test
    void multiValueDimensionCapsAtMaxItems() {
        InMemoryProfileStore store = new InMemoryProfileStore();
        ProfileService service = newService(store, new FakeChatPort());
        for (int i = 0; i < 5; i++) {
            service.addExplicit(ProfileItem.OwnerType.USER, null, "u1", "design_habits",
                    "习惯" + i, null);
        }
        // maxItems=3 → 只留最近 3 条
        assertEquals(3, service.getItems(ProfileItem.OwnerType.USER, null, "u1",
                "design_habits").size());
    }

    @Test
    void unregisteredDimensionIsRejected() {
        ProfileService service = newService(new InMemoryProfileStore(), new FakeChatPort());
        CYException e = assertThrows(CYException.class, () -> service.addExplicit(
                ProfileItem.OwnerType.USER, null, "u1", "unknown_dim", "x", null));
        assertEquals(50624, e.getErrorCode());
    }

    @Test
    void extractedEntrySupersedesContradictedItem() {
        InMemoryProfileStore store = new InMemoryProfileStore();
        ProfileService seed = newService(store, new FakeChatPort());
        ProfileItem old = seed.addExplicit(ProfileItem.OwnerType.USER, null, "u1", "branch",
                "陆军", null);
        // 提取器回指矛盾条目：新值入库前旧值被确定性取代，同一主体不允许两种说法共存
        FakeChatPort chat = new FakeChatPort().reply(
                "[{\"dimension\":\"branch\",\"value\":\"海军\",\"inferred\":false,"
                        + "\"evidence\":\"我现在是海军了\",\"supersedes\":[\"" + old.getItemId() + "\"]}]");
        ProfileService service = newService(store, chat);
        int saved = service.extractAndUpdate(ProfileItem.OwnerType.USER, null, "u1",
                "用户：我现在是海军了");
        assertEquals(1, saved);
        List<ProfileItem> active = service.getItems(ProfileItem.OwnerType.USER, null, "u1", "branch");
        assertEquals(1, active.size());
        assertEquals("海军", active.get(0).getValue());
        assertEquals(ProfileItem.Status.SUPERSEDED,
                store.get(old.getItemId()).orElseThrow().getStatus());
    }

    @Test
    void supersedesCannotCrossOwner() {
        InMemoryProfileStore store = new InMemoryProfileStore();
        ProfileService seed = newService(store, new FakeChatPort());
        ProfileItem other = seed.addExplicit(ProfileItem.OwnerType.USER, null, "u2", "branch",
                "空军", null);
        // u1 的提取结果试图取代 u2 的条目 → 越权拒绝，u2 条目不受影响
        FakeChatPort chat = new FakeChatPort().reply(
                "[{\"dimension\":\"branch\",\"value\":\"海军\",\"inferred\":false,"
                        + "\"supersedes\":[\"" + other.getItemId() + "\"]}]");
        ProfileService service = newService(store, chat);
        service.extractAndUpdate(ProfileItem.OwnerType.USER, null, "u1", "用户：我是海军");
        assertEquals(ProfileItem.Status.ACTIVE,
                store.get(other.getItemId()).orElseThrow().getStatus());
    }

    @Test
    void renderRespectsDimensionOrderAndBudget() {
        InMemoryProfileStore store = new InMemoryProfileStore();
        ProfileService service = newService(store, new FakeChatPort());
        service.addExplicit(ProfileItem.OwnerType.USER, null, "u1", "branch", "陆军", null);
        service.addExplicit(ProfileItem.OwnerType.USER, null, "u1", "move_semantics", "A", null);
        List<String> lines = service.render(ProfileItem.OwnerType.USER, null, "u1", 0);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("军种："));
        // 预算=1 → 第一行也放不下时为空（预算截断）
        List<String> tight = service.render(ProfileItem.OwnerType.USER, null, "u1", 1);
        assertTrue(tight.isEmpty());
    }
}
