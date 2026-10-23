package com.cy.orm.config;

import com.cy.orm.core.domain.BaseEntity;
import com.cy.orm.listener.EntityInsertListener;
import com.cy.orm.listener.EntityUpdateListener;
import com.mybatisflex.core.FlexGlobalConfig;
import com.mybatisflex.core.audit.AuditManager;
import com.mybatisflex.core.audit.ConsoleMessageCollector;
import com.mybatisflex.core.audit.MessageCollector;
import com.mybatisflex.core.mybatis.FlexConfiguration;
import com.mybatisflex.core.query.QueryColumnBehavior;
import com.mybatisflex.spring.boot.ConfigurationCustomizer;
import com.mybatisflex.spring.boot.MyBatisFlexCustomizer;
import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;


/**
 * mybatis-flex配置
 *
 * @author pcy
 */
@EnableTransactionManagement(proxyTargetClass = true)
@AutoConfiguration
@Configuration
public class MyBatisFlexConfig implements ConfigurationCustomizer, MyBatisFlexCustomizer {

    /**
     * 是否开启审计功能，默认为false
     */
    @Value("${mybatis-flex.audit_enable:false}")
    private Boolean enableAudit = false;

    /**
     * 是否打印SQL到控制台，默认为false
     */
    @Value("${mybatis-flex.sql_print:false}")
    private Boolean sqlPrint = false;

    static {
        QueryColumnBehavior.setIgnoreFunction(QueryColumnBehavior.IGNORE_BLANK);
        QueryColumnBehavior.setSmartConvertInToEquals(true);
    }


    @Override
    public void customize(FlexConfiguration configuration) {
        //mybatis实现的打印详细sql及返回结果到控制台，便于调试
        if (sqlPrint) {
            configuration.setLogImpl(StdOutImpl.class);
        }
    }

    /**
     * Mybatis-Flex自定义初始化配置
     *
     * @param globalConfig 全局配置
     */
    @Override
    public void customize(FlexGlobalConfig globalConfig) {
        // 注册全局数据填充监听器
        globalConfig.registerInsertListener(new EntityInsertListener(), BaseEntity.class);
        globalConfig.registerUpdateListener(new EntityUpdateListener(), BaseEntity.class);

        // 开启审计功能
        AuditManager.setAuditEnable(enableAudit);
        if (sqlPrint) {
            // 开启sql打印默认会开启sql审计
            AuditManager.setAuditEnable(true);
            //设置 SQL 审计收集器
            MessageCollector collector = new ConsoleMessageCollector();
            AuditManager.setMessageCollector(collector);
        }
    }

}
