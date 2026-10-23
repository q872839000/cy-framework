-- ============================================================
-- 智能会话组件数据库表初始化脚本
-- 
-- 包含以下表：
-- 1. conversation_session - 会话表
-- 2. conversation_message - 聊天消息表
-- 
-- @author pcy
-- @date 2026/1/26
-- ============================================================

-- 会话表
CREATE TABLE IF NOT EXISTS conversation_session (
    id BIGINT PRIMARY KEY COMMENT '主键ID（雪花算法）',
    title VARCHAR(50) NOT NULL DEFAULT '新的对话' COMMENT '会话标题',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '会话状态：0-已归档，1-正常',
    naming_status TINYINT NOT NULL DEFAULT 0 COMMENT '命名状态：0-待命名，1-命名中，2-已命名，3-命名失败',
    message_count INT NOT NULL DEFAULT 0 COMMENT '消息数量',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    create_user BIGINT COMMENT '创建者ID',
    update_user BIGINT COMMENT '更新者ID',
    deleted INT DEFAULT 0 COMMENT '删除标志：0-未删除，1-已删除',
    llm_provider VARCHAR(64) COMMENT '会话级 LLM 提供商偏好（cy.ai.providers 的 key；NULL=跟随平台默认）',
    llm_model VARCHAR(128) COMMENT '会话级 LLM 模型覆盖（NULL=提供商默认模型）',
    thinking_pref VARCHAR(16) COMMENT '会话级思考偏好（off/on/low/medium/high；NULL=跟随 provider 全局配置）',
    KEY idx_create_user (create_user) COMMENT '创建者索引，用于查询用户会话列表',
    KEY idx_update_time (update_time) COMMENT '更新时间索引，用于排序',
    KEY idx_status (status) COMMENT '状态索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话表';

-- 聊天消息表
CREATE TABLE IF NOT EXISTS conversation_message (
    id BIGINT PRIMARY KEY COMMENT '主键ID（雪花算法）',
    session_id BIGINT NOT NULL COMMENT '所属会话ID',
    role VARCHAR(20) NOT NULL COMMENT '消息角色：user/assistant/system',
    content LONGTEXT COMMENT '消息内容',
    attachments LONGTEXT COMMENT '附件列表（JSON 数组，元素为图片地址：data URI 或 http(s) URL）',
    reasoning_content LONGTEXT COMMENT '思考内容',
    blocks_json LONGTEXT COMMENT '过程快照（JSON：events/reasonings/textBlocks/artifacts/meta，供历史回看重建执行过程）',
    tokens INT DEFAULT 0 COMMENT 'Token消耗数量',
    model VARCHAR(64) COMMENT '使用的模型名称',
    duration_ms BIGINT DEFAULT 0 COMMENT '响应耗时（毫秒）',
    first_token_ms BIGINT DEFAULT 0 COMMENT '首帧响应耗时（毫秒）',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    create_user BIGINT COMMENT '创建者ID',
    KEY idx_session_id (session_id) COMMENT '会话ID索引',
    KEY idx_session_time (session_id, create_time) COMMENT '会话+时间复合索引，用于按时间查询消息'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天消息表';
