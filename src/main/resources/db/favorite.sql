USE campus_trade;

CREATE TABLE favorite (
    id          BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '收藏ID',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    product_id  BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
    deleted     TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',

    -- 唯一索引：同一个用户不能重复收藏同一个商品
    -- 这是防重复收藏的核心，比在 Java 代码里查一次再判断更可靠
    UNIQUE INDEX uk_user_product (user_id, product_id),
    
    -- 单列索引：加速按用户查询"我的收藏"
    INDEX idx_user_id (user_id),
    
    -- 单列索引：加速按商品查询"谁收藏了这个商品"（未来可能用到）
    INDEX idx_product_id (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户收藏表';