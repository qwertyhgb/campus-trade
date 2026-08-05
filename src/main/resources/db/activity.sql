-- 活动主表
-- 执行前提：activity_category 表已存在（见 activity_category.sql）
-- 执行顺序：activity_category.sql → activity.sql → reservation.sql
-- 可重复执行，不会报错（所有索引均内联在 CREATE TABLE IF NOT EXISTS 中）

USE campus_trade;

-- ============================================================
-- 活动主表
-- ============================================================
CREATE TABLE IF NOT EXISTS activity (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '活动ID',

    -- ===== 基本信息 =====
    title VARCHAR(100) NOT NULL COMMENT '活动标题',
    description TEXT COMMENT '活动详细描述',
    location VARCHAR(200) NOT NULL COMMENT '活动地点',
    cover_image VARCHAR(255) COMMENT '封面图片URL',
    category_id BIGINT UNSIGNED NOT NULL COMMENT '所属分类ID',

    -- ===== 时间信息 =====
    start_time DATETIME NOT NULL COMMENT '活动开始时间',
    end_time DATETIME NOT NULL COMMENT '活动结束时间',
    enroll_start_time DATETIME NOT NULL COMMENT '报名开始时间',
    enroll_end_time DATETIME NOT NULL COMMENT '报名截止时间',

    -- ===== 容量信息 =====
    max_count INT UNSIGNED NOT NULL COMMENT '最大参与人数',
    current_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前已预约人数',

    -- ===== 状态 =====
    status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '状态：0草稿 1待审核 2审核拒绝 3报名中 4报名结束 5进行中 6已结束 7已下架',

    -- ===== 组织者 =====
    organizer_id BIGINT UNSIGNED NOT NULL COMMENT '组织者用户ID',

    -- ===== 审核信息 =====
    reviewer_id BIGINT UNSIGNED COMMENT '审核人用户ID',
    review_time DATETIME COMMENT '审核时间',
    reject_reason VARCHAR(500) COMMENT '拒绝原因（审核拒绝时填写）',

    -- ===== 通用字段 =====
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',

    -- ===== 索引设计 =====

    -- 索引1：活动列表页（按状态筛选 + 按时间排序）
    -- 场景：用户浏览“报名中”的活动，按创建时间倒序
    -- SQL：WHERE status = 3 AND deleted = 0 ORDER BY create_time DESC
    -- 最左前缀：
    --   ✅ WHERE status=? ORDER BY create_time → 完全命中
    --   ✅ WHERE status=?                      → 命中最左列
    --   ❌ ORDER BY create_time                → 未从 status 开始，无法使用
    INDEX idx_status_create (status, create_time),

    -- 索引2：按分类筛选
    -- 场景：用户点击“体育竞技”分类，查看该分类下报名中的活动
    -- SQL：WHERE category_id = ? AND status = 3
    -- 最左前缀：
    --   ✅ WHERE category_id=? AND status=? → 完全命中
    --   ✅ WHERE category_id=?              → 命中最左列
    --   ❌ WHERE status=?                   → 未从 category_id 开始，由 idx_status_create 处理
    INDEX idx_category_status (category_id, status),

    -- 索引3：组织者查看自己的活动
    -- 场景：组织者进入“我的活动”页面
    -- SQL：WHERE organizer_id = ? ORDER BY create_time DESC
    -- 最左前缀：
    --   ✅ WHERE organizer_id=? ORDER BY create_time → 完全命中，无需 filesort
    --   ✅ WHERE organizer_id=?                      → 命中最左列
    --   ❌ ORDER BY create_time                      → 未从 organizer_id 开始
    INDEX idx_organizer_create (organizer_id, create_time),

    -- 索引4：定时任务 - 报名截止转状态
    -- 场景：每分钟扫描“报名中且已过截止时间”的活动
    -- SQL：WHERE status = 3 AND enroll_end_time < NOW()
    -- 最左前缀：
    --   ✅ WHERE status=3 AND enroll_end_time < ? → 完全命中（等值+范围）
    --   ✅ WHERE status=3                        → 命中最左列
    INDEX idx_status_enroll_end (status, enroll_end_time),

    -- 索引5：定时任务 - 活动开始转状态
    -- 场景：每分钟扫描“报名结束且已过开始时间”的活动
    -- SQL：WHERE status = 4 AND start_time < NOW()
    -- 最左前缀：
    --   ✅ WHERE status=4 AND start_time < ? → 完全命中
    --   ✅ WHERE status=4                    → 命中最左列
    INDEX idx_status_start (status, start_time),

    -- 索引6：定时任务 - 活动结束转状态
    -- 场景：每分钟扫描“进行中且已过结束时间”的活动
    -- SQL：WHERE status = 5 AND end_time < NOW()
    -- 最左前缀：
    --   ✅ WHERE status=5 AND end_time < ? → 完全命中
    --   ✅ WHERE status=5                  → 命中最左列
    INDEX idx_status_end (status, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='活动表';