-- 活动分类表
-- 执行前提：无（独立表）
-- 可重复执行，不会报错

USE campus_trade;

-- ============================================================
-- 活动分类表
-- ============================================================
CREATE TABLE IF NOT EXISTS activity_category (
    id          BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '分类ID',
    name        VARCHAR(50)     NOT NULL COMMENT '分类名称',
    sort        INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    -- 分类名唯一，防止重复创建同名分类
    UNIQUE KEY uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='活动分类表';

-- 插入初始分类（幂等：已存在则跳过）
INSERT IGNORE INTO activity_category (name, sort) VALUES
('学术讲座', 1),
('体育竞技', 2),
('文艺演出', 3),
('社团活动', 4),
('志愿服务', 5),
('就业创业', 6);