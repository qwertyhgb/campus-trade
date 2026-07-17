USE campus_trade;

CREATE TABLE user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    username VARCHAR(50) NOT NULL COMMENT '用户名',
    password VARCHAR(100) NOT NULL COMMENT '密码',
    nickname VARCHAR(50) COMMENT '昵称',
    phone VARCHAR(20) COMMENT '手机号',
    avatar VARCHAR(255) COMMENT '头像',
    status TINYINT DEFAULT 1 COMMENT '状态：1正常，0禁用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除'
);

-- 给用户名添加唯一索引
ALTER TABLE `user` ADD UNIQUE KEY `uk_username` (`username`);

-- 给手机号添加唯一索引（若已有重复的手机号，需先清理数据再执行此句）
ALTER TABLE `user` ADD UNIQUE KEY `uk_phone` (`phone`);

-- 将表和所有字符字段的字符集转换为 utf8mb4
ALTER TABLE `user` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 调整 ID 为无符号
ALTER TABLE `user` MODIFY `id` BIGINT UNSIGNED AUTO_INCREMENT COMMENT '用户ID';

-- 优化其余状态与删除标记字段为无符号
ALTER TABLE `user` MODIFY `status` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态：1正常，0禁用';
ALTER TABLE `user` MODIFY `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除';

