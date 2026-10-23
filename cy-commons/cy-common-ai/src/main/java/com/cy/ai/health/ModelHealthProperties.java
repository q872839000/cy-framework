package com.cy.ai.health;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 大模型健康检查配置。
 *
 * @author pcy
 */
@Data
@ConfigurationProperties(prefix = "cy.ai.health")
public class ModelHealthProperties {

    /**
     * 是否启用大模型健康检查（默认关）。
     * <p>开启后后台维护可用状态并由宿主暴露查询接口。注意：实际的 bean 装配条件由
     * {@code @ConditionalOnProperty} 读取本键，故关闭时整套健康检查（含接口）一并不注册。</p>
     */
    private boolean enabled = false;

    /**
     * 极简探测的整体超时（连接 + 读取上限），超时即判不可用。
     * <p>
     * 探测已关思考且 {@code max_tokens=1}，正常模型首 token 延迟通常在 1~2s 内，故默认 5s 留足余量。
     * 模型冷启动、加载权重或排队较重的环境可调大（如 {@code 10s}）；但该值也是接口在缓存不可用时
     * 当场补探的等待上限，过大将拉长前端拿到"不可用"的时间，需权衡。
     * </p>
     */
    private Duration timeout = Duration.ofSeconds(5);
}
