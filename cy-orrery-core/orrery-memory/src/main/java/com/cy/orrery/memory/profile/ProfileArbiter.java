// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.profile;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 画像仲裁器（同维度新旧条目的冲突裁决，确定性规则不依赖模型自觉）。
 * <p>
 * 规则：显式 &gt; 推断（推断值不得覆盖显式值）；同性质时新证据优先。
 * 单值维度：接受则旧条目 SUPERSEDE。多值维度：去重追加，超上限先逐出
 * 最旧的推断条目，再逐出最旧条目。
 * </p>
 *
 * @author pcy
 */
public class ProfileArbiter {

    private static final Logger log = LoggerFactory.getLogger(ProfileArbiter.class);

    /** 时间源。 */
    private final Clock clock;

    /**
     * 构造仲裁器。
     *
     * @param clock 时间源
     */
    public ProfileArbiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * 仲裁结果。
     *
     * @param accepted   是否接受新条目
     * @param reason     拒绝原因（接受时为 null）
     * @param toSupersede 需被取代的旧条目
     */
    public record Verdict(boolean accepted, String reason, List<ProfileItem> toSupersede) {
        /** 拒绝。 */
        static Verdict reject(String reason) {
            return new Verdict(false, reason, List.of());
        }

        /** 接受。 */
        static Verdict accept(List<ProfileItem> toSupersede) {
            return new Verdict(true, null, toSupersede);
        }
    }

    /**
     * 裁决新条目是否入库、哪些旧条目被取代。
     *
     * @param dimension 维度定义
     * @param incoming  新条目（未入库）
     * @param existing  该属主该维度下的 ACTIVE 旧条目（createdAt 升序）
     * @return 裁决
     */
    public Verdict arbitrate(ProfileDimension dimension, ProfileItem incoming,
                             List<ProfileItem> existing) {
        if (dimension.multiValue()) {
            return arbitrateMulti(dimension, incoming, existing);
        }
        return arbitrateSingle(incoming, existing);
    }

    /** 单值维度裁决。 */
    private Verdict arbitrateSingle(ProfileItem incoming, List<ProfileItem> existing) {
        List<ProfileItem> supersede = new ArrayList<>();
        for (ProfileItem old : existing) {
            if (sameValue(old.getValue(), incoming.getValue())) {
                return Verdict.reject("同维度同值已存在");
            }
            if (incoming.isInferred() && !old.isInferred()) {
                log.debug("画像推断值不覆盖显式值: dimension={}, owner={}",
                        incoming.getDimension(), incoming.getOwnerId());
                return Verdict.reject("推断值不得覆盖显式值");
            }
            supersede.add(old);
        }
        return Verdict.accept(supersede);
    }

    /** 多值维度裁决。 */
    private Verdict arbitrateMulti(ProfileDimension dimension, ProfileItem incoming,
                                   List<ProfileItem> existing) {
        for (ProfileItem old : existing) {
            if (sameValue(old.getValue(), incoming.getValue())) {
                return Verdict.reject("同维度同值已存在");
            }
        }
        List<ProfileItem> supersede = new ArrayList<>();
        int overflow = existing.size() + 1 - dimension.maxItems();
        if (overflow > 0) {
            List<ProfileItem> candidates = new ArrayList<>(existing);
            // 先逐出最旧的推断条目，推断不足再逐出最旧显式条目
            candidates.sort((a, b) -> {
                if (a.isInferred() != b.isInferred()) {
                    return a.isInferred() ? -1 : 1;
                }
                return a.getCreatedAt().compareTo(b.getCreatedAt());
            });
            for (int i = 0; i < overflow && i < candidates.size(); i++) {
                supersede.add(candidates.get(i));
            }
        }
        return Verdict.accept(supersede);
    }

    /**
     * 应用裁决结果（条目状态迁移 + 新条目入库字段填充）。
     *
     * @param store     画像存储
     * @param verdict   裁决
     * @param incoming  新条目
     * @return 入库后的新条目（拒绝返回 null）
     */
    public ProfileItem apply(ProfileStore store, Verdict verdict, ProfileItem incoming) {
        if (!verdict.accepted()) {
            return null;
        }
        Instant now = clock.instant();
        for (ProfileItem old : verdict.toSupersede()) {
            ProfileItem after = old.copy();
            after.setStatus(ProfileItem.Status.SUPERSEDED);
            after.setUpdatedAt(now);
            after.setVersion(old.getVersion() + 1);
            if (!store.update(after, old.getVersion())) {
                log.warn("画像旧条目取代版本冲突（跳过取代）: itemId={}", old.getItemId());
            }
        }
        incoming.setStatus(ProfileItem.Status.ACTIVE);
        incoming.setCreatedAt(now);
        incoming.setUpdatedAt(now);
        incoming.setVersion(1);
        store.save(incoming);
        return incoming;
    }

    /** 值归一化比较（去首尾空白、忽略大小写）。 */
    private boolean sameValue(String a, String b) {
        return a != null && b != null && a.strip().equalsIgnoreCase(b.strip());
    }
}
