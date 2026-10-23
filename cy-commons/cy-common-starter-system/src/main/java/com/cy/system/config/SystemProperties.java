package com.cy.system.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 系统管理模块配置属性
 */
@ConfigurationProperties(prefix = "cy.system")
public class SystemProperties {

    /**
     * 是否启用系统管理模块
     */
    private boolean enabled = true;

    /**
     * 是否自动初始化表结构
     */
    private boolean tableInit = true;

    /**
     * API路径前缀
     */
    private String apiPrefix = "/system";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTableInit() {
        return tableInit;
    }

    public void setTableInit(boolean tableInit) {
        this.tableInit = tableInit;
    }

    public String getApiPrefix() {
        return apiPrefix;
    }

    public void setApiPrefix(String apiPrefix) {
        this.apiPrefix = apiPrefix;
    }
}
