package com.cy.log.request;

/**
 * 请求信息解析器接口
 * <p>
 * 从当前请求上下文中提取 HTTP 信息。不同运行时环境（Servlet / WebFlux / gRPC）
 * 提供各自的实现，日志核心层通过此 SPI 解耦对具体 Web 框架的依赖。
 *
 * @author pcy
 * @date 2026/04/14
 */
public interface RequestInfoResolver {

    /**
     * 解析当前请求信息
     *
     * @return 请求信息，无法解析时返回 null
     */
    RequestInfo resolve();
}
