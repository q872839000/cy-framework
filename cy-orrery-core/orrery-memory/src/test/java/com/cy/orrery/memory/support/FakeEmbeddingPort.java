// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.support;

import com.cy.orrery.memory.api.EmbeddingPort;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 测试用向量化端口（字符袋哈希：内容重叠度高的文本相似度高）。
 *
 * @author pcy
 */
public class FakeEmbeddingPort implements EmbeddingPort {

    /** 向量维度。 */
    private static final int DIM = 16;

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIM];
        if (text == null) {
            return vector;
        }
        for (String token : text.split("\\s+|，|。|、|：|:|,")) {
            if (token.isEmpty()) {
                continue;
            }
            CRC32 crc = new CRC32();
            crc.update(token.getBytes(StandardCharsets.UTF_8));
            vector[(int) (crc.getValue() % DIM)] += 1f;
        }
        return vector;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        for (String text : texts) {
            out.add(embed(text));
        }
        return out;
    }

    @Override
    public int dimension() {
        return DIM;
    }

    @Override
    public boolean available() {
        return true;
    }
}
