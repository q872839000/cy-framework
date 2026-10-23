package com.cy.log.request;

import lombok.Data;

/**
 * 请求信息数据对象
 * <p>
 * 从当前请求上下文中提取的 HTTP 信息，用于解耦日志组件对 Servlet API 的直接依赖。
 *
 * @author pcy
 * @date 2026/04/14
 */
@Data
public class RequestInfo {

    /**
     * 请求 URI
     */
    private String requestUri;

    /**
     * HTTP 请求方法
     */
    private String requestMethod;

    /**
     * 客户端 IP 地址
     */
    private String clientIp;
}
