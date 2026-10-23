package com.cy.orm.utils;

import com.mybatisflex.core.keygen.impl.SnowFlakeIDKeyGenerator;
import com.mybatisflex.core.keygen.impl.UUIDKeyGenerator;

/**
 * @Auther: pcy
 * @Date: 2025/10/9 10:25
 * @Description:
 */
public class IdGeneratedUtil {

    private static final SnowFlakeIDKeyGenerator snowFlakeIDKeyGenerator = new SnowFlakeIDKeyGenerator();

    private static final UUIDKeyGenerator uuidKeyGenerator = new UUIDKeyGenerator();

    public static Long generateId() {
        return snowFlakeIDKeyGenerator.nextId();
    }

    public static String generateUUID() {
        return uuidKeyGenerator.generate(null, null).toString();
    }
}
