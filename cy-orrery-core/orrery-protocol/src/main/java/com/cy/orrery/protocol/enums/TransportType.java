// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * 传输协议类型枚举
 * <p>
 * 定义 Agent 在能力描述中声明的通信传输方式。
 * 具体调用实现由编排方或调用方根据自身职责选择。
 * </p>
 *
 * @author pcy
 * @date 2026/04/15
 */
@Getter
public enum TransportType {

    HTTP("http", "HTTP/HTTPS 请求"),
    GRPC("grpc", "gRPC 双向流"),
    WEBSOCKET("websocket", "WebSocket 长连接");

    private final String value;
    private final String description;

    TransportType(String value, String description) {
        this.value = value;
        this.description = description;
    }

    /**
     * 根据字符串值查找枚举（忽略大小写）
     *
     * @param value 传输类型值
     * @return 对应枚举，未找到返回 null
     */
    public static TransportType fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (TransportType type : values()) {
            if (type.value.equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return null;
    }
}
