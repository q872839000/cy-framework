package com.cy.milvus.config;

import lombok.Data;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @Auther: pcy
 * @Date: 2025/9/15 15:26
 * @Description: Milvus配置类
 */
@Data
@ConfigurationProperties(prefix = "cy.milvus")
public class MilvusProperties {

    /** Milvus 服务地址 */
    private String host;

    /** Milvus 服务端口 */
    private int port = 19530;

    /** 访问 Token，可选 */
    private String token = "";

    /** 连接超时时间 */
    private Duration timeout = Duration.ofSeconds(30);

    /** 是否连接，默认连接，可配置不连接 **/
    private Boolean isConnect = true;
}
