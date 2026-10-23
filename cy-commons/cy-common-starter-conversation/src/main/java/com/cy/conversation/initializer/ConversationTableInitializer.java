package com.cy.conversation.initializer;

import com.cy.conversation.config.ConversationProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * 会话表初始化器
 * <p>
 * 在应用启动时自动执行SQL脚本创建所需的数据库表。
 * 使用IF NOT EXISTS语法，确保表已存在时不会报错。
 * </p>
 *
 * @author pcy
 * @date 2026/1/26
 */
public class ConversationTableInitializer {

    private static final Logger log = LoggerFactory.getLogger(ConversationTableInitializer.class);

    /**
     * SQL初始化脚本路径
     */
    private static final String INIT_SQL_PATH = "sql/conversation_init_tables.sql";

    /**
     * SQL升级脚本路径（增量变更，允许失败）
     */
    private static final String UPGRADE_SQL_PATH = "sql/conversation_upgrade_tables.sql";

    private final ConversationProperties properties;
    private final DataSource dataSource;

    /**
     * 构造函数
     *
     * @param properties 配置属性
     * @param dataSource 数据源
     */
    public ConversationTableInitializer(ConversationProperties properties, DataSource dataSource) {
        this.properties = properties;
        this.dataSource = dataSource;
    }

    /**
     * 初始化数据库表
     * <p>
     * 在Bean创建后自动执行，加载并执行SQL脚本
     * </p>
     */
    @PostConstruct
    public void initTables() {
        if (!properties.isTableInit()) {
            log.info("会话表初始化已禁用");
            return;
        }

        try {
            ClassPathResource resource = new ClassPathResource(INIT_SQL_PATH);
            if (!resource.exists()) {
                log.warn("会话表初始化SQL脚本不存在: {}", INIT_SQL_PATH);
                return;
            }

            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(resource);
            populator.setSeparator(";");
            populator.setContinueOnError(false);

            populator.execute(dataSource);

            log.info("会话表初始化成功");

        } catch (Exception e) {
            log.error("会话表初始化失败", e);
            // 不抛出异常，允许应用继续启动（表可能已存在）
        }

        // 执行增量升级脚本（允许失败，字段可能已存在）
        runUpgradeScripts();
    }

    /**
     * 执行增量升级脚本
     * <p>
     * 使用 continueOnError=true，ALTER TABLE ADD COLUMN 在字段已存在时会报错，
     * 忽略该错误即可。
     * </p>
     */
    private void runUpgradeScripts() {
        try {
            ClassPathResource resource = new ClassPathResource(UPGRADE_SQL_PATH);
            if (!resource.exists()) {
                return;
            }

            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(resource);
            populator.setSeparator(";");
            populator.setContinueOnError(true);

            populator.execute(dataSource);

            log.info("会话表升级脚本执行完成");

        } catch (Exception e) {
            log.warn("会话表升级脚本执行异常（可忽略）: {}", e.getMessage());
        }
    }
}
