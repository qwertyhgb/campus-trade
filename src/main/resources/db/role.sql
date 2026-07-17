USE campus_trade;

-- 给 user 表添加 role 字段：0=普通用户，1=管理员
ALTER TABLE `user` 
ADD COLUMN `role` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '角色：0普通用户，1管理员' AFTER `status`;

-- 给现有管理员账号设置角色（如果你有一个测试管理员账号的话）
-- UPDATE `user` SET role = 1 WHERE username = 'admin';