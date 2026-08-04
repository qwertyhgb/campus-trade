USE campus_trade;

-- ========================================================================
-- 商品留言表（comment）
-- ========================================================================
-- 业务说明：
--   用户可以对商品进行留言/提问，卖家或其他用户可以回复。
--   通过 parent_id 实现两级结构：顶级留言（parent_id=NULL）和回复（parent_id=被回复留言ID）。
--   reply_to_user_id 是反范式化的冗余字段，用于前端展示"回复 @xxx"，
--   避免每次展示时还要 JOIN 父留言去查被回复者是谁。
--
-- 索引设计说明：
--
-- 1. 为什么需要索引？
--    商品详情页的留言区是高频读取场景：每个用户打开商品详情都会加载留言列表。
--    随着留言数据增长（热门商品可能有数百条留言），没有索引时每次查询都要全表扫描，
--    响应时间会从毫秒级退化到秒级。
--
-- 2. 核心查询有哪些？
--    (a) 商品详情页留言列表：
--        SELECT * FROM comment WHERE product_id=? AND deleted=0 ORDER BY create_time DESC
--        （MyBatis-Plus 逻辑删除会自动追加 AND deleted=0）
--    (b) 查看某条留言的所有回复：
--        SELECT * FROM comment WHERE parent_id=? AND deleted=0 ORDER BY create_time ASC
--    (c) 我的留言列表：
--        SELECT * FROM comment WHERE user_id=? AND deleted=0 ORDER BY create_time DESC
--
-- 3. 联合索引如何设计？
--    针对查询 (a)，联合索引 (product_id, deleted, create_time)：
--    - product_id 等值过滤放最左（定位到某个商品的留言）
--    - deleted 等值过滤放中间（过滤掉已删除的）
--    - create_time 放最后（利用索引有序性直接排序，避免 filesort）
--    三列联合完美覆盖 WHERE + ORDER BY，一次索引扫描即可拿到有序结果。
-- ========================================================================

CREATE TABLE IF NOT EXISTS comment (
    id               BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '留言ID',
    product_id       BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    user_id          BIGINT UNSIGNED NOT NULL COMMENT '留言用户ID',
    content          VARCHAR(500) NOT NULL COMMENT '留言内容',
    parent_id        BIGINT UNSIGNED DEFAULT NULL COMMENT '父留言ID（NULL=顶级留言，非NULL=回复某条留言）',
    reply_to_user_id BIGINT UNSIGNED DEFAULT NULL COMMENT '被回复的用户ID（回复时记录，用于展示"回复 @xxx"）',
    create_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '留言时间',
    update_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',

    -- 单列索引：查“我的留言”列表
    INDEX idx_user_id (user_id),
    -- 单列索引：查“某条留言的所有回复”
    INDEX idx_parent_id (parent_id),

    -- 联合索引 1：商品详情页留言列表（最高频查询）
    -- SQL：SELECT * FROM comment WHERE product_id=? AND deleted=0 ORDER BY create_time DESC
    -- 查询频率：极高（每个用户打开商品详情页都会触发）
    -- 最左前缀：
    --   ✅ WHERE product_id=? AND deleted=0 ORDER BY create_time → 完全命中
    --   ✅ WHERE product_id=? AND deleted=0                      → 命中（前两列）
    --   ✅ WHERE product_id=?                                    → 命中（最左列）
    --   ❌ WHERE deleted=0 ORDER BY create_time                  → 未从 product_id 开始
    INDEX idx_productid_deleted_createtime (product_id, deleted, create_time),

    -- 联合索引 2：查看某条留言的回复列表
    -- SQL：SELECT * FROM comment WHERE parent_id=? AND deleted=0 ORDER BY create_time ASC
    -- 查询频率：中等（用户展开某条留言的回复时触发）
    -- 最左前缀：
    --   ✅ WHERE parent_id=? AND deleted=0 ORDER BY create_time → 完全命中
    --   ✅ WHERE parent_id=? AND deleted=0                      → 命中（前两列）
    --   ✅ WHERE parent_id=?                                    → 命中（最左列）
    --   ❌ WHERE deleted=0 ORDER BY create_time                 → 未从 parent_id 开始
    INDEX idx_parentid_deleted_createtime (parent_id, deleted, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商品留言表';
