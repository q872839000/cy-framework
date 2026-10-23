package com.cy.web.tracing;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 链路追踪自动装配。
 * <p>
 * 通过 {@link BeanPostProcessor} 为容器中所有 {@link RestTemplate} Bean
 * 自动添加 {@link TracingClientHttpRequestInterceptor}（无论它是由
 * {@code RestTemplateBuilder} 还是 {@code new RestTemplate()} 创建的）。
 * <p>
 * 同时为 {@code WebClient.Builder} / {@code WebClient} 自动添加
 * {@link TracingExchangeFilterFunction}。
 *
 * @author pcy
 * @date 2026/04/09
 */
@AutoConfiguration
public class TracingAutoConfiguration {

    /**
     * 后置处理器：为所有 RestTemplate Bean 注入 tracing 拦截器。
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.web.client.RestTemplate")
    public static BeanPostProcessor tracingRestTemplateBeanPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof RestTemplate restTemplate) {
                    List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>(restTemplate.getInterceptors());
                    boolean alreadyPresent = interceptors.stream()
                            .anyMatch(i -> i instanceof TracingClientHttpRequestInterceptor);
                    if (!alreadyPresent) {
                        interceptors.add(new TracingClientHttpRequestInterceptor());
                        restTemplate.setInterceptors(interceptors);
                    }
                }
                return bean;
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.reactive.function.client.ExchangeFilterFunction")
    static class WebClientTracingConfiguration {

        @Bean
        public static BeanPostProcessor tracingWebClientBeanPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (bean instanceof org.springframework.web.reactive.function.client.WebClient.Builder builder) {
                        builder.filters(filters -> {
                            boolean alreadyPresent = filters.stream()
                                    .anyMatch(f -> f instanceof TracingExchangeFilterFunction);
                            if (!alreadyPresent) {
                                filters.add(new TracingExchangeFilterFunction());
                            }
                        });
                        return builder;
                    }
                    if (bean instanceof org.springframework.web.reactive.function.client.WebClient webClient) {
                        return webClient.mutate()
                                .filters(filters -> {
                                    boolean alreadyPresent = filters.stream()
                                            .anyMatch(f -> f instanceof TracingExchangeFilterFunction);
                                    if (!alreadyPresent) {
                                        filters.add(new TracingExchangeFilterFunction());
                                    }
                                })
                                .build();
                    }
                    return bean;
                }
            };
        }

        @Bean
        public TracingExchangeFilterFunction tracingExchangeFilterFunction() {
            return new TracingExchangeFilterFunction();
        }
    }
}
