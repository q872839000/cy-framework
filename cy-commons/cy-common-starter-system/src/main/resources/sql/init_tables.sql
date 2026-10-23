CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    username VARCHAR(64) NOT NULL COMMENT '用户名',
    password VARCHAR(128) NOT NULL COMMENT '密码',
    nickname VARCHAR(64) COMMENT '昵称',
    email VARCHAR(128) COMMENT '邮箱',
    phone VARCHAR(20) COMMENT '手机号',
    avatar MEDIUMTEXT COMMENT '头像（支持BASE64编码或图片URL，前端可直接渲染）',
    gender TINYINT DEFAULT 0 COMMENT '性别：0-未知，1-男，2-女',
    status TINYINT DEFAULT 1 COMMENT '状态：0-禁用，1-正常',
    remark VARCHAR(512) COMMENT '备注',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    create_user BIGINT COMMENT '创建者ID',
    update_user BIGINT COMMENT '更新者ID',
    deleted INT DEFAULT 0 COMMENT '删除标志：0-未删除，1-已删除',
    UNIQUE KEY uk_username (username),
    KEY idx_phone (phone),
    KEY idx_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    role_code VARCHAR(64) NOT NULL COMMENT '角色编码',
    role_name VARCHAR(64) NOT NULL COMMENT '角色名称',
    sort INT DEFAULT 0 COMMENT '排序',
    status TINYINT DEFAULT 1 COMMENT '状态：0-禁用，1-正常',
    remark VARCHAR(512) COMMENT '备注',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    create_user BIGINT COMMENT '创建者ID',
    update_user BIGINT COMMENT '更新者ID',
    UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';

CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    parent_id BIGINT DEFAULT 0 COMMENT '父菜单ID',
    menu_name VARCHAR(64) NOT NULL COMMENT '菜单名称',
    menu_type TINYINT NOT NULL COMMENT '菜单类型：1-目录，2-菜单，3-按钮',
    path VARCHAR(256) COMMENT '路由地址',
    component VARCHAR(256) COMMENT '组件路径',
    permission VARCHAR(128) COMMENT '权限标识',
    icon VARCHAR(64) COMMENT '图标',
    sort INT DEFAULT 0 COMMENT '排序',
    visible TINYINT DEFAULT 1 COMMENT '是否可见：0-隐藏，1-显示',
    status TINYINT DEFAULT 1 COMMENT '状态：0-禁用，1-正常',
    remark VARCHAR(512) COMMENT '备注',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    create_user BIGINT COMMENT '创建者ID',
    update_user BIGINT COMMENT '更新者ID',
    KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜单表';

CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_user_id (user_id),
    KEY idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';

CREATE TABLE IF NOT EXISTS sys_role_menu (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    menu_id BIGINT NOT NULL COMMENT '菜单ID',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_role_menu (role_id, menu_id),
    KEY idx_role_id (role_id),
    KEY idx_menu_id (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色菜单关联表';

CREATE TABLE IF NOT EXISTS sys_file (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名称',
    original_file_name VARCHAR(255) COMMENT '原始文件名',
    suffix VARCHAR(32) COMMENT '文件后缀',
    file_size BIGINT DEFAULT 0 COMMENT '文件大小（字节）',
    file_path VARCHAR(512) COMMENT '文件存储路径',
    md5_hash VARCHAR(32) COMMENT '文件MD5哈希值',
    unzipped TINYINT DEFAULT 0 COMMENT '是否已解压：0-未解压，1-已解压',
    parent_file_id BIGINT COMMENT '压缩包父文件ID',
    remark VARCHAR(512) COMMENT '文件备注',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    create_user BIGINT COMMENT '创建者ID',
    update_user BIGINT COMMENT '更新者ID',
    KEY idx_file_name (file_name),
    KEY idx_md5_hash (md5_hash),
    KEY idx_parent_file_id (parent_file_id),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统文件表';
