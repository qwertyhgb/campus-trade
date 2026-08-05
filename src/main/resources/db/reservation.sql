-- 预约表与候补表
-- 执行前提：activity 表已存在（见 activity.sql）
-- 执行顺序：activity_category.sql → activity.sql → reservation.sql
-- 可重复执行，不会报错（所有索引和约束均内联在 CREATE TABLE IF NOT EXISTS 中）
--
-- 设计说明：两表都不设 deleted 字段（物理保留 + 状态标记）。
--   预约/候补是历史痕迹，取消后需要保留记录用于统计和分析，
--   但"有效性"通过 active_mark + status 表达，而不是删除记录。

USE campus_trade;

-- ============================================================
-- 预约表
-- ============================================================
-- active_mark 部分唯一索引技巧：
--   MySQL 不支持部分索引（PARTIAL INDEX），但唯一索引允许多个 NULL 值。
--   因此用 active_mark=1 表示"有效"，active_mark=NULL 表示"无效"。
--   唯一索引 uk_user_activity_active (user_id, activity_id, active_mark)：
--     - 用户对同一活动只能有 1 条有效预约（active_mark=1 时受约束）
--     - 取消/失效后的历史记录 active_mark=NULL，不受约束，可以多条
--   注意：active_mark 与 status 必须保持一致性，由应用层保证：
--     status=0（已预约）→ active_mark=1
--     status=1/2（已取消/已失效）→ active_mark=NULL
CREATE TABLE IF NOT EXISTS reservation (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '预约ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '预约用户ID',
    activity_id BIGINT UNSIGNED NOT NULL COMMENT '活动ID',

    -- 状态：0已预约 1已取消 2已失效
    status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '状态：0已预约 1已取消 2已失效',

    -- 活跃标记：有效记录=1，无效记录=NULL（配合唯一索引实现"部分唯一"约束）
    active_mark TINYINT UNSIGNED DEFAULT 1 COMMENT '活跃标记：1有效，NULL无效',

    -- 取消/失效时间
    cancel_time DATETIME COMMENT '取消或失效时间',

    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '预约时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 核心唯一索引：同一用户同一活动只能有一条有效预约（详见表头注释）
    UNIQUE KEY uk_user_activity_active (user_id, activity_id, active_mark),

    -- 索引1：组织者查看某活动的预约名单
    -- SQL：WHERE activity_id = ? AND status = 0 ORDER BY create_time
    -- 最左前缀：
    --   ✅ WHERE activity_id=? AND status=? ORDER BY create_time → 完全命中
    --   ✅ WHERE activity_id=?                                  → 命中最左列
    --   ❌ ORDER BY create_time                                 → 未从 activity_id 开始
    INDEX idx_activity_status (activity_id, status, create_time),

    -- 索引2：用户查看"我的预约"
    -- SQL：WHERE user_id = ? ORDER BY create_time DESC
    -- 最左前缀：
    --   ✅ WHERE user_id=? ORDER BY create_time → 完全命中，无需 filesort
    --   ✅ WHERE user_id=?                      → 命中最左列
    --   ❌ ORDER BY create_time                 → 未从 user_id 开始
    INDEX idx_user_create (user_id, create_time),

    -- CHECK 约束：保证 status 与 active_mark 的一致性（MySQL 8.0.16+ 生效）
    -- status=0（已预约）→ active_mark 必须为 1
    -- status=1/2（已取消/已失效）→ active_mark 必须为 NULL
    CONSTRAINT chk_reservation_status_active CHECK (
        (status = 0 AND active_mark = 1)
        OR
        (status IN (1, 2) AND active_mark IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='预约表';


-- ============================================================
-- 候补表
-- ============================================================
-- active_mark 技巧与预约表完全一致：
--   同一用户同一活动只能有一条有效候补（uk_user_activity_active）
-- queue_position 说明：
--   补位时取队首（queue_position 最小者），补位后需要把后面的候补者
--   位置前移一位（应用层在事务中处理）
CREATE TABLE IF NOT EXISTS waiting_list (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '候补ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '候补用户ID',
    activity_id BIGINT UNSIGNED NOT NULL COMMENT '活动ID',

    -- 排队位置（从1开始，越小越靠前）
    queue_position INT UNSIGNED NOT NULL COMMENT '排队位置',

    -- 状态：0候补中 1已补位 2已取消 3已失效
    status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '状态：0候补中 1已补位 2已取消 3已失效',

    -- 活跃标记（同预约表的思路）：0候补中→1，其他状态→NULL
    active_mark TINYINT UNSIGNED DEFAULT 1 COMMENT '活跃标记：1有效，NULL无效',

    -- 补位/取消/失效时间
    process_time DATETIME COMMENT '状态变更时间（补位/取消/失效）',

    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入候补时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 核心唯一索引：同一用户同一活动只能有一条有效候补
    UNIQUE KEY uk_user_activity_active (user_id, activity_id, active_mark),

    -- 位置唯一索引：同一活动中有效候补位置不能重复
    -- 历史记录 active_mark=NULL 不受约束，可以多条
    UNIQUE KEY uk_activity_position_active (activity_id, queue_position, active_mark),

    -- 索引3：补位时取队首
    -- SQL：WHERE activity_id = ? AND status = 0 ORDER BY queue_position ASC LIMIT 1
    -- 最左前缀：
    --   ✅ WHERE activity_id=? AND status=? ORDER BY queue_position → 完全命中
    --   ✅ WHERE activity_id=?                                    → 命中最左列
    --   ❌ ORDER BY queue_position                                → 未从 activity_id 开始
    INDEX idx_activity_status_position (activity_id, status, queue_position),

    -- 索引4：用户查看"我的候补"
    -- SQL：WHERE user_id = ? ORDER BY create_time DESC
    -- 最左前缀：
    --   ✅ WHERE user_id=? ORDER BY create_time → 完全命中
    --   ✅ WHERE user_id=?                      → 命中最左列
    --   ❌ ORDER BY create_time                 → 未从 user_id 开始
    INDEX idx_user_create (user_id, create_time),

    -- CHECK 约束：保证 status 与 active_mark 的一致性（MySQL 8.0.16+ 生效）
    -- status=0（候补中）→ active_mark 必须为 1
    -- status=1/2/3（已补位/已取消/已失效）→ active_mark 必须为 NULL
    CONSTRAINT chk_waiting_status_active CHECK (
        (status = 0 AND active_mark = 1)
        OR
        (status IN (1, 2, 3) AND active_mark IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='候补表';