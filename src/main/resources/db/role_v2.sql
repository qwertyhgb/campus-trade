-- 文件路径：src/main/resources/db/role_v2.sql
--
-- ============================================================
-- RBAC 权限模型迁移脚本（v2）
-- ============================================================
-- 背景：原系统用 user.role 单个整数字段表示角色（0=普通用户，1=管理员），
--       扩展性差——一个用户只能有一个角色，无法表达多角色或更细粒度的权限。
--       本脚本引入标准的 RBAC（基于角色的访问控制）模型：
--         role       —— 角色定义表
--         user_role  —— 用户-角色多对多关联表
--       并将 user.role 的存量数据迁移到 user_role。
--
-- 重要：本脚本只做"数据层"迁移。应用层仍需配套改造为从 user_role 读取角色。待改造完成并验证无误后，
--       才能考虑废弃（DROP）旧的 user.role 字段。切勿在应用层切换前删除该字段。

USE campus_trade;

-- ============================================================
-- 1. 角色表：存储系统中所有角色定义
-- ============================================================
CREATE TABLE IF NOT EXISTS role (
    id          BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '角色ID',
    role_code   VARCHAR(30)  NOT NULL COMMENT '角色编码（英文标识，如 USER、ADMIN）',
    role_name   VARCHAR(50)  NOT NULL COMMENT '角色名称（中文显示，如 普通用户、管理员）',
    description VARCHAR(200) DEFAULT NULL COMMENT '角色描述',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    -- role_code 必须唯一，不允许出现两个 ADMIN
    UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色表';

-- 插入初始角色（与校园二手交易平台的实际业务对应）。
-- 活动模块使用 ORGANIZER（组织活动）和 AUDITOR（审核活动），
-- 因此角色定义必须和 SecurityConfig 中的 hasRole 保持一致。
-- INSERT IGNORE 让脚本可以重复执行：已有角色不会重复插入。
INSERT IGNORE INTO role (role_code, role_name, description) VALUES
('USER',  '普通用户',   '可浏览/搜索商品、发布商品、购买、收藏、留言评价'),
('ORGANIZER', '活动组织者', '可创建、编辑、提交自己组织的活动'),
('AUDITOR', '活动审核员', '可审核待审核的活动'),
('ADMIN', '系统管理员', '拥有所有权限，包括商品审核、订单管理、分类管理、封禁用户');


-- ============================================================
-- 2. 用户-角色关联表：多对多中间表
-- ============================================================
-- 设计说明：与收藏表（favorite）一样采用"物理删除"，不设 deleted 字段。
--           因为存在联合唯一索引 uk_user_role，若改用逻辑删除，取消角色后记录残留（deleted=1），
--           重新分配同一角色时会触发唯一约束冲突。物理删除可彻底规避此问题。
CREATE TABLE IF NOT EXISTS user_role (
    id          BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    role_id     BIGINT UNSIGNED NOT NULL COMMENT '角色ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '分配时间',
    -- 联合唯一索引：同一个用户不能被分配同一个角色两次
    UNIQUE KEY uk_user_role (user_id, role_id),
    -- 普通索引：按角色查用户（比如"查出所有管理员"）
    INDEX idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户角色关联表';


-- ============================================================
-- 3. 数据迁移：把现有 user.role 字段的数据同步到 user_role 表
-- ============================================================
-- 用事务包裹 DML，保证两条迁移语句要么都成功、要么都回滚，避免迁移到一半产生脏数据。
-- （CREATE TABLE 属于 DDL，会隐式提交，无法纳入事务；这里只对 INSERT 做事务保护。）
START TRANSACTION;

-- 3.1 所有未删除的现有用户至少拥有 USER 角色
--     NOT EXISTS 保证脚本可重复执行（幂等）：已分配 USER 角色的用户不会被重复插入
INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
FROM user u
JOIN role r ON r.role_code = 'USER'
WHERE u.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id
  );

-- 3.2 原来 role=1 的管理员，额外分配 ADMIN 角色
INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
FROM user u
JOIN role r ON r.role_code = 'ADMIN'
WHERE u.role = 1
  AND u.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id
  );

COMMIT;


-- ============================================================
-- 4. 后续事项（不在本脚本执行，仅作提醒）
-- ============================================================
-- 待应用层完成"从 user_role 读取角色"的改造并上线验证后，再单独执行：
--     ALTER TABLE user DROP COLUMN role;
-- 在此之前保留 user.role 字段作为回滚兜底，切勿提前删除。
