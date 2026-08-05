-- 用户表
-- 执行前提：无（基础表）
-- 可重复执行，不会报错（所有索引和约束均内联在 CREATE TABLE IF NOT EXISTS 中）
-- 执行顺序：user.sql → role.sql → role_v2.sql

USE campus_trade;

CREATE TABLE IF NOT EXISTS `user` (
    id          BIGINT UNSIGNED  PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    username    VARCHAR(50)      NOT NULL COMMENT '用户名',
    password    VARCHAR(100)     NOT NULL COMMENT '密码',
    nickname    VARCHAR(50)      DEFAULT NULL COMMENT '昵称',
    phone       VARCHAR(20)      DEFAULT NULL COMMENT '手机号',
    avatar      VARCHAR(255)     DEFAULT NULL COMMENT '头像',
    status      TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态：1正常，0禁用',
    create_time DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',

    -- 用户名唯一索引：数据库层面防止用户名重复（比 Java 代码判断更可靠）
    UNIQUE KEY uk_username (username),
    -- 手机号唯一索引：一个手机号只能注册一个账号
    -- 注意：phone 允许 NULL，MySQL 唯一索引允许多个 NULL，不受影响
    UNIQUE KEY uk_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户表';

-- ============================================================
-- 旧表升级参考（仅用于已存在旧版 user 表的环境，新环境无需执行）
-- 当前环境已是最新结构，以下语句不要重复执行，否则会因索引已存在而报错
-- ============================================================
-- ALTER TABLE `user` ADD UNIQUE KEY `uk_username` (`username`);
-- ALTER TABLE `user` ADD UNIQUE KEY `uk_phone` (`phone`);
-- ALTER TABLE `user` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
-- ALTER TABLE `user` MODIFY `id` BIGINT UNSIGNED AUTO_INCREMENT COMMENT '用户ID';
-- ALTER TABLE `user` MODIFY `status` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态：1正常，0禁用';
-- ALTER TABLE `user` MODIFY `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除';
