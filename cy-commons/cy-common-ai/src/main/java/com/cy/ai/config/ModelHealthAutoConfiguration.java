package com.cy.ai.config;

import com.cy.ai.AiFacade;
import com.cy.ai.health.ModelHealthProperties;
import com.cy.ai.health.ModelHealthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 大模型健康检查自动配置。
 * <p>
 * 仅在 {@code cy.ai.health.enabled=true} 且容器内已存在 {@link AiFacade} 时注册
 * {@link ModelHealthService}。默认关闭——避免所有依赖 cy-common-ai 的服务都无条件启动后台探测;
 * 需要的服务(如 scenario-agent)在配置里显式打开开关即可,这就是"服务只提供接口"的那一侧。
 * </p>
 * <p>
 * {@link ModelHealthService} 的定时刷新依赖宿主应用开启 {@code @EnableScheduling};
 * 启动自检走 {@link org.springframework.boot.context.event.ApplicationReadyEvent} 无需额外开关。
 * </p>
 *
 * @author pcy
 * @see ModelHealthService
 */
@AutoConfiguration(after = AiAutoConfiguration.class)
@EnableConfigurationProperties(ModelHealthProperties.class)
@ConditionalOnProperty(prefix = "cy.ai.health", name = "enabled", havingValue = "true")
public class ModelHealthAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModelHealthAutoConfiguration.class);

    @Bean
    @ConditionalOnBean(AiFacade.class)
    @ConditionalOnMissingBean
    public ModelHealthService modelHealthService(AiFacade aiFacade, ModelHealthProperties properties) {
        log.info("大模型健康检查已启用(cy.ai.health.enabled=true, timeout={})", properties.getTimeout());
        return new ModelHealthService(aiFacade, properties);
    }
}
