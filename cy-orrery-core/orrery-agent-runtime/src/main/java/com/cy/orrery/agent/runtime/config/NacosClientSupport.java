// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.config;

import com.alibaba.nacos.api.PropertyKeyConst;
import java.util.Properties;
import org.springframework.core.env.Environment;

/**
 * Nacos 客户端连接参数装配（identity 身份发布与 assets 提示词资产两通道共用）。
 * <p>
 * 覆盖值优先，缺省回落 {@code spring.cloud.nacos.*} 共享配置，最终兜底
 * {@link #DEFAULT_SERVER_ADDR}；空白值不写入（避免空串覆盖 SDK 默认）。
 * </p>
 *
 * @author pcy
 */
public final class NacosClientSupport {

    /** Nacos 默认地址（与 Spring Cloud Alibaba 缺省一致）。 */
    public static final String DEFAULT_SERVER_ADDR = "localhost:8848";

    private NacosClientSupport() {
    }

    /**
     * 装配 Nacos 客户端连接参数。
     *
     * @param serverAddr 服务地址（空白用默认值）
     * @param username   用户名（可空）
     * @param password   密码（可空）
     * @param namespace  命名空间（可空）
     * @return 连接参数
     */
    public static Properties toClientProperties(String serverAddr, String username,
                                                String password, String namespace) {
        Properties props = new Properties();
        put(props, PropertyKeyConst.SERVER_ADDR,
                firstNonBlank(serverAddr, DEFAULT_SERVER_ADDR));
        put(props, PropertyKeyConst.USERNAME, username);
        put(props, PropertyKeyConst.PASSWORD, password);
        put(props, PropertyKeyConst.NAMESPACE, namespace);
        return props;
    }

    /**
     * 取首个非空白值（无则 null）。
     *
     * @param candidates 候选值（按优先级排列）
     * @return 首个非空白值
     */
    public static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    /** 空白值不写入（空串会覆盖 SDK 内置默认）。 */
    private static void put(Properties props, String key, String value) {
        if (value != null && !value.isBlank()) {
            props.setProperty(key, value);
        }
    }
}
