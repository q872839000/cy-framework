package com.cy.milvus.config;



import com.cy.milvus.core.MilvusClientManager;
import com.cy.milvus.service.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Auther: pcy
 * @Date: 2025/9/15 15:42
 * @Description: Milvus Spring Boot 自动配置类
 * 引入 Starter 后，会自动创建 MilvusClientManager 及各核心 Service Bean
 */
@Configuration
@EnableConfigurationProperties(MilvusProperties.class)
public class MilvusAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MilvusClientManager milvusClientManager(MilvusProperties props) {
        return new MilvusClientManager(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public CollectionService collectionService(MilvusClientManager clientManager) {
        return new CollectionService(clientManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public PartitionService partitionService(MilvusClientManager clientManager) {
        return new PartitionService(clientManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public DataService dataService(MilvusClientManager clientManager) {
        return new DataService(clientManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public IndexService indexService(MilvusClientManager clientManager) {
        return new IndexService(clientManager);
    }
}