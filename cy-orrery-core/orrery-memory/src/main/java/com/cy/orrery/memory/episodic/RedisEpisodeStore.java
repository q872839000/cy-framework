// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.episodic;

import com.cy.core.exception.CYException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.Optional;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 情景卡片 Redis 存储（键 {@code orrery:mem:episode:{episodeId}}，TTL 滑动续期）。
 *
 * @author pcy
 */
public class RedisEpisodeStore implements EpisodeStore {

    private static final Logger log = LoggerFactory.getLogger(RedisEpisodeStore.class);

    /** 键前缀。 */
    private static final String KEY_PREFIX = "orrery:mem:episode:";

    /** Redisson 客户端。 */
    private final RedissonClient redisson;

    /** JSON 编解码。 */
    private final ObjectMapper objectMapper;

    /** TTL。 */
    private final Duration ttl;

    /**
     * 构造 Redis 情景卡片存储。
     *
     * @param redisson Redisson 客户端
     * @param ttl      卡片 TTL（每次写入滑动续期）
     */
    public RedisEpisodeStore(RedissonClient redisson, Duration ttl) {
        this.redisson = redisson;
        this.ttl = ttl;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public void save(EpisodeRecord record) {
        try {
            redisson.getBucket(KEY_PREFIX + record.getEpisodeId(), StringCodec.INSTANCE)
                    .set(objectMapper.writeValueAsString(record), ttl);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new CYException(50617, e.getMessage());
        }
    }

    @Override
    public Optional<EpisodeRecord> get(String episodeId) {
        RBucket<String> bucket = redisson.getBucket(KEY_PREFIX + episodeId, StringCodec.INSTANCE);
        String json = bucket.get();
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, EpisodeRecord.class));
        } catch (JsonProcessingException e) {
            // 卡片损坏按缺失处理，并删除坏数据防止每次读取都命中它
            log.warn("情景卡片快照损坏（按缺失处理并清除）: episodeId={}", episodeId);
            bucket.delete();
            return Optional.empty();
        }
    }

    @Override
    public void delete(String episodeId) {
        redisson.getBucket(KEY_PREFIX + episodeId, StringCodec.INSTANCE).delete();
    }

    @Override
    public boolean available() {
        return true;
    }
}
