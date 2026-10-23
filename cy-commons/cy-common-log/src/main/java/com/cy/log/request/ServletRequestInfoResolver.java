package com.cy.log.request;

import com.cy.log.util.LogUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Servlet 环境下的请求信息解析器
 * <p>
 * 从 {@link RequestContextHolder} 获取当前 HTTP 请求并提取关键信息。
 * 仅在 Servlet 容器（Tomcat / Undertow / Jetty）环境下自动装配。
 *
 * @author pcy
 * @date 2026/04/14
 */
public class ServletRequestInfoResolver implements RequestInfoResolver {

    @Override
    public RequestInfo resolve() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            RequestInfo info = new RequestInfo();
            info.setRequestUri(request.getRequestURI());
            info.setRequestMethod(request.getMethod());
            info.setClientIp(LogUtils.getClientIp(request));
            return info;
        } catch (Exception e) {
            return null;
        }
    }
}
