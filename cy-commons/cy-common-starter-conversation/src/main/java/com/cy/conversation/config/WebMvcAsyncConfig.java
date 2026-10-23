package com.cy.conversation.config;

import com.cy.web.config.ServletAsyncInvocationContextConfigurer;
import org.springframework.context.annotation.Configuration;

/**
 * WebMvc异步配置类
 * <p>
 * 配置Spring MVC的异步请求处理，用于SSE流式响应等场景
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
@Configuration
public class WebMvcAsyncConfig extends ServletAsyncInvocationContextConfigurer {
}
