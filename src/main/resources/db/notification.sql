-- 站内通知表
-- 执行前提：user 表已存在（见 user.sql）
-- 可重复执行，不会报错（所有索引均内联在 CREATE TABLE IF NOT EXISTS 中）
--
-- 设计说明：不设 deleted 字段。
--   通知是用户的业务历史记录（预约成功/取消/候补补位/审核结果等），
--   已读/未读通过 is_read 标记表达，无需物理或逻辑删除。

USE campus_trade;

-- ============================================================
-- 站内通知表
-- ============================================================
CREATE TABLE IF NOT EXISTS notification (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '通知ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '接收者用户ID',

    -- 通知类型：1预约成功 2预约取消 3加入候补 4候补补位成功 5审核通过 6审核拒绝 7活动即将开始
    type TINYINT UNSIGNED NOT NULL COMMENT '通知类型',

    title VARCHAR(100) NOT NULL COMMENT '通知标题',
    content VARCHAR(500) NOT NULL COMMENT '通知内容',

    -- 关联的业务ID（活动ID或预约ID，方便前端跳转）
    related_id BIGINT UNSIGNED COMMENT '关联业务ID',

    -- 是否已读
    is_read TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否已读：0未读 1已读',

    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '通知创建时间',

    -- 索引1：用户查看自己的通知列表（按时间倒序）
    -- SQL：WHERE user_id = ? ORDER BY create_time DESC
    -- 最左前缀：
    --   ✅ WHERE user_id=? ORDER BY create_time → 完全命中，无需 filesort
    --   ✅ WHERE user_id=?                      → 命中最左列
    --   ❌ ORDER BY create_time                 → 未从 user_id 开始
    INDEX idx_user_create (user_id, create_time),

    -- 索引2：用户查看未读通知数量
    -- SQL：WHERE user_id = ? AND is_read = 0
    -- 最左前缀：
    --   ✅ WHERE user_id=? AND is_read=? → 完全命中
    --   ✅ WHERE user_id=?               → 命中最左列
    --   ❌ WHERE is_read=0               → 未从 user_id 开始
    INDEX idx_user_read (user_id, is_read)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='站内通知表';
