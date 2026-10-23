-- ============================================================
-- 智能会话组件数据库表升级脚本
--
-- 用于已有数据库的增量升级，新增字段使用 ALTER TABLE。
-- 每条语句独立执行，允许失败（字段可能已存在）。
--
-- @author pcy
-- @date 2026/3/11
-- ============================================================

-- v1.1: 消息表新增思考内容字段
ALTER TABLE conversation_message ADD COLUMN reasoning_content LONGTEXT COMMENT '思考内容' AFTER content;

-- v1.2: 消息表新增附件列（多模态图片输入，JSON 数组）
ALTER TABLE conversation_message ADD COLUMN attachments LONGTEXT COMMENT '附件列表（JSON 数组，元素为图片地址：data URI 或 http(s) URL）' AFTER content;

-- v1.3: 会话表新增会话级模型选择字段（大模型集中管理，换模型对话无需新建会话）
ALTER TABLE conversation_session ADD COLUMN llm_provider VARCHAR(64) COMMENT '会话级 LLM 提供商偏好（cy.ai.providers 的 key；NULL=跟随平台默认）' AFTER deleted;
ALTER TABLE conversation_session ADD COLUMN llm_model VARCHAR(128) COMMENT '会话级 LLM 模型覆盖（NULL=提供商默认模型）' AFTER llm_provider;

-- v1.4: 会话表新增思考偏好字段（页面思考控制，与模型偏好同通道钉到会话）
ALTER TABLE conversation_session ADD COLUMN thinking_pref VARCHAR(16) COMMENT '会话级思考偏好（off/on/low/medium/high；NULL=跟随 provider 全局配置）' AFTER llm_model;

-- v1.5: 消息表新增过程快照列（一次运行的执行过程聚合，历史回看重建执行过程视图）
ALTER TABLE conversation_message ADD COLUMN blocks_json LONGTEXT COMMENT '过程快照（JSON：events/reasonings/textBlocks/artifacts/meta，供历史回看重建执行过程）' AFTER reasoning_content;
