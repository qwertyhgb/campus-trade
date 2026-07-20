USE campus_trade;

-- 收藏表：记录用户收藏了哪些商品
-- 注意：此表采用「物理删除」，没有 deleted 字段。
-- 原因：唯一索引 uk_user_product(user_id, product_id) 保证同一用户不能重复收藏同一商品。
-- 如果使用逻辑删除（deleted 字段），取消收藏后记录仍留在表中（deleted=1），
-- 再次收藏时 INSERT 会触发唯一约束冲突，导致收藏静默失败。
-- 物理删除后记录真正从表中移除，再次收藏可以正常写入。
CREATE TABLE favorite (
    id          BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '收藏ID',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    product_id  BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',

    -- 唯一索引：同一个用户不能重复收藏同一个商品
    -- 这是防重复收藏的核心，比在 Java 代码里查一次再判断更可靠
    -- 物理删除后记录不存在了，再次收藏不会触发冲突
    UNIQUE INDEX uk_user_product (user_id, product_id),
    
    -- 单列索引：加速按用户查询"我的收藏"
    INDEX idx_user_id (user_id),
    
    -- 单列索引：加速按商品查询"谁收藏了这个商品"（未来可能用到）
    INDEX idx_product_id (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户收藏表';

-- 【升级迁移】如果你之前已经建过带 deleted 字段的旧表，执行以下语句移除该字段：
-- ALTER TABLE favorite DROP COLUMN deleted;
