package com.cy.mq.core;

import com.cy.mq.config.MqProperties;
import lombok.RequiredArgsConstructor;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 驱动注册中心
 *
 * @author pcy
 */
@RequiredArgsConstructor
public class MqDriverRegistry {

    /**
     * 组件配置
     */
    private final MqProperties properties;

    /**
     * 驱动映射
     */
    private final Map<MqDriverType, MqDriver> driverMap = new EnumMap<>(MqDriverType.class);

    /**
     * 注册驱动
     *
     * @param drivers 驱动列表
     */
    public void registerDrivers(List<MqDriver> drivers) {
        for (MqDriver driver : drivers) {
            driverMap.put(driver.type(), driver);
        }
    }

    public boolean hasDriver(MqDriverType requested) {
        MqDriverType type = resolveDriverType(requested);
        return type != null && driverMap.containsKey(type);
    }

    public Set<MqDriverType> registeredDriverTypes() {
        return Set.copyOf(driverMap.keySet());
    }

    /**
     * 获取驱动
     *
     * @param requested 期望驱动（null 表示使用全局默认驱动）
     * @return 驱动
     */
    public MqDriver getDriver(MqDriverType requested) {
        MqDriverType type = resolveDriverType(requested);
        if (type == null) {
            throw new IllegalStateException("未配置默认 MQ 驱动。请显式配置 cy.mq.driver，或在当前发布/订阅调用中显式指定 driver。已注册驱动: "
                    + registeredDriverTypes());
        }
        MqDriver driver = driverMap.get(type);
        if (driver == null) {
            throw new IllegalStateException("未找到消息队列驱动: " + type + "，已注册驱动: " + registeredDriverTypes());
        }
        return driver;
    }

    public MqDriverType resolveDriverType(MqDriverType requested) {
        return requested == null ? properties.getDriver() : requested;
    }
}
