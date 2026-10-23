-- orrery-memory 表结构（MySQL 8.x；时间列统一 BIGINT epoch millis）
-- 生产环境建议手工执行；开发可开 cy.orrery.memory.jdbc-auto-init=true 代建

-- 长期记忆本体（L2）
CREATE TABLE IF NOT EXISTS orrery_memory_item (
    memory_id         BIGINT       NOT NULL COMMENT '记忆 ID（雪花）',
    tenant_id         VARCHAR(64)  NULL COMMENT '租户 ID',
    scope_type        VARCHAR(16)  NOT NULL COMMENT '归属类型：SESSION/USER/AGENT/TENANT',
    owner_id          VARCHAR(64)  NOT NULL COMMENT '属主 ID',
    kind              VARCHAR(16)  NOT NULL COMMENT '类型：FACT/PREFERENCE/EXPERIENCE',
    abstract_text     VARCHAR(255) NOT NULL COMMENT '一句话索引',
    content           TEXT         NOT NULL COMMENT '记忆正文',
    source_ref        VARCHAR(255) NULL COMMENT '来源指针（sessionId#runId）',
    importance        INT          NOT NULL DEFAULT 5 COMMENT '重要度 1-10',
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/SUPERSEDED/INVALIDATED',
    version           BIGINT       NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    access_count      BIGINT       NOT NULL DEFAULT 0 COMMENT '召回命中计数',
    created_at        BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    updated_at        BIGINT       NOT NULL COMMENT '更新时刻（epoch millis）',
    last_accessed_at  BIGINT       NULL COMMENT '最近召回时刻（epoch millis）',
    PRIMARY KEY (memory_id),
    KEY idx_mem_scope (tenant_id, scope_type, owner_id, status),
    KEY idx_mem_tombstone (status, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='长期记忆本体';

-- 记忆审计链
CREATE TABLE IF NOT EXISTS orrery_memory_revision (
    revision_id BIGINT       NOT NULL COMMENT '修订 ID（雪花）',
    memory_id   BIGINT       NOT NULL COMMENT '记忆 ID',
    op          VARCHAR(16)  NOT NULL COMMENT '操作：CREATE/UPDATE/SUPERSEDE/INVALIDATE/ERASE/PURGE',
    before_json MEDIUMTEXT   NULL COMMENT '变更前快照（ERASE/PURGE 为脱敏占位）',
    after_json  MEDIUMTEXT   NULL COMMENT '变更后快照',
    actor_id    VARCHAR(64)  NULL COMMENT '操作者',
    reason      VARCHAR(512) NULL COMMENT '依据',
    at          BIGINT       NOT NULL COMMENT '发生时刻（epoch millis）',
    PRIMARY KEY (revision_id),
    KEY idx_rev_memory (memory_id, at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='记忆审计链';

-- 用户画像条目（维度由消费方 ProfileDimensionProvider 定义）
CREATE TABLE IF NOT EXISTS orrery_memory_profile_item (
    item_id    BIGINT       NOT NULL COMMENT '条目 ID（雪花）',
    owner_type VARCHAR(16)  NOT NULL COMMENT '属主类型：USER/AGENT',
    owner_id   VARCHAR(64)  NOT NULL COMMENT '属主 ID',
    tenant_id  VARCHAR(64)  NULL COMMENT '租户 ID',
    dimension  VARCHAR(64)  NOT NULL COMMENT '维度 key（消费方注册）',
    value      VARCHAR(1024) NOT NULL COMMENT '条目值',
    inferred   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否行为推断（0=显式陈述）',
    evidence   VARCHAR(1024) NULL COMMENT '证据（对话原文）',
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/SUPERSEDED/INVALIDATED',
    version    BIGINT       NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_at BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    updated_at BIGINT       NOT NULL COMMENT '更新时刻（epoch millis）',
    PRIMARY KEY (item_id),
    KEY idx_profile_owner (owner_type, owner_id, tenant_id, dimension, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户画像条目';
