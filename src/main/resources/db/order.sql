USE campus_trade;

-- ========================================================================
-- 订单表（order）
-- ========================================================================
-- 索引设计说明：
--
-- 1. 为什么需要索引？
--    订单表是交易系统的核心表，数据量会随着交易增长快速增长（大学校园平台
--    日订单量可能在数千到上万）。没有索引时，每次查询都需要全表扫描，
--    随着数据量增加性能会急剧下降。索引相当于书的目录，MySQL 通过 B+Tree
--    结构可以快速定位到目标数据，将 O(n) 的全表扫描降为 O(log n) 的树查找。
--
-- 2. 为什么单列索引不够？
--    订单业务的核心查询有两个：
--      (a) SELECT * FROM `order` WHERE buyer_id=? ORDER BY create_time DESC
--      (b) SELECT * FROM `order` WHERE seller_id=? ORDER BY create_time DESC
--    如果只在 buyer_id 上建单列索引，MySQL 可以通过索引快速定位到某个买家
--    的所有订单（用到了索引），但对 ORDER BY create_time DESC 无法再利用
--    索引排序，因为从 buyer_id 索引中找到的记录，其 create_time 是分散的，
--    MySQL 必须在内存中做 filesort（文件排序），属于"先索引过滤，再额外排序"，
--    当订单量很大时性能低下。
--
-- 3. 什么是联合索引（复合索引）？
--    联合索引是指一个索引包含多个列，如 idx_buyerid_createtime(buyer_id, create_time)。
--    B+Tree 先按第一个列（buyer_id）排序，在 buyer_id 相同的情况下再按第二个列
--    （create_time）排序。这样既能在 WHERE buyer_id=? 时快速定位，又能直接利用
--    索引中已经排好序的 create_time 来满足 ORDER BY，完全避免 filesort。
--
-- 4. 什么是最左前缀原则？
--    最左前缀原则（Leftmost Prefix Principle）是 MySQL 使用联合索引的核心规则：
--    只有从联合索引的最左边列开始的查询才能用到该索引。
--    例如 idx_buyerid_createtime(buyer_id, create_time)：
--      ✅ WHERE buyer_id=1                     → 能用（匹配最左列）
--      ✅ WHERE buyer_id=1 ORDER BY create_time → 能用（匹配最左列 + 利用第二列排序）
--      ✅ WHERE buyer_id=1 AND create_time>...  → 能用（范围查询后不能再用后续列排序）
--      ❌ WHERE create_time>...                → 不能用到索引（没从最左列开始）
--    联合索引本质上是一个"按列优先级排序"的结构，第一列决定整体顺序，
--    第二列在第一列相同时有效，第三列在前两列相同时有效，依此类推。
-- ========================================================================

CREATE TABLE `order` (
                         id              BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '订单ID',
                         order_no        VARCHAR(32) NOT NULL COMMENT '订单号（用于展示，如 ORD2026070214301234）',
                         product_id      BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
                         product_title   VARCHAR(100) NOT NULL COMMENT '商品标题快照（下单时的标题）',
                         product_price   DECIMAL(10,2) NOT NULL COMMENT '商品价格快照（下单时的价格）',
                         product_image   VARCHAR(255) COMMENT '商品图片快照（下单时的图片）',
                         buyer_id        BIGINT UNSIGNED NOT NULL COMMENT '买家ID',
                         seller_id       BIGINT UNSIGNED NOT NULL COMMENT '卖家ID',
                         status          TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '订单状态：0待确认 1已完成 2已取消',
                         create_time     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                         update_time     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                         deleted         TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',

                         -- 订单号唯一索引：保证每个订单号全局唯一，同时支持按订单号精确查询
                         UNIQUE INDEX uk_order_no (order_no),
                         -- 以下单列索引作为"备用"：在某些只需要 buyer_id/seller_id 精确匹配不做排序的场景下，
                         -- MySQL 优化器可能会选择单列索引而不是更"重"的联合索引（索引体积更小，遍历成本更低）
                         INDEX idx_buyer_id  (buyer_id),
                         INDEX idx_seller_id (seller_id),
                         INDEX idx_product_id (product_id),
                         INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单表';

-- ========================================================================
-- 联合索引 1：查询"我买到的订单"并排序
-- ========================================================================
-- SQL 原型：SELECT * FROM `order` WHERE buyer_id=? ORDER BY create_time DESC
-- 查询频率：极高（买家每次打开"我买到的"页面都会触发）
--
-- 为什么不用单列索引 idx_buyer_id？
--   idx_buyer_id 能快速定位买家记录，但无法处理 ORDER BY create_time，
--   导致 Extra 列中出现 Using filesort，需要额外的排序操作。
--
-- 联合索引 idx_buyerid_createtime(buyer_id, create_time) 的优势：
--   B+Tree 按 buyer_id 排序，相同 buyer_id 的记录已按 create_time 有序排列，
--   MySQL 在索引中定位到 buyer_id=? 的起始位置后，直接向后（或向前）扫描即可
--   拿到有序数据，完全消除 filesort，性能大幅提升。
--
-- 最左前缀实践：
--   ✅ WHERE buyer_id=? ORDER BY create_time DESC    → 命中索引，无需额外排序
--   ✅ WHERE buyer_id=?                               → 命中索引（只用最左列）
--   ❌ ORDER BY create_time                           → 未从 buyer_id 开始，无法使用
-- ========================================================================
ALTER TABLE `order` ADD INDEX idx_buyerid_createtime (buyer_id, create_time);

-- ========================================================================
-- 联合索引 2：查询"我卖出的订单"并排序
-- ========================================================================
-- SQL 原型：SELECT * FROM `order` WHERE seller_id=? ORDER BY create_time DESC
-- 查询频率：极高（卖家每次打开"我卖出的"页面都会触发）
--
-- 和 idx_buyerid_createtime 的设计思路完全对称：
-- 联合索引 (seller_id, create_time) 同时覆盖 WHERE 过滤和 ORDER BY 排序，
-- 避免 filesort。
--
-- 为什么不把 buyer_id 和 seller_id 放在同一个联合索引中？
--   因为 buyer_id 和 seller_id 是独立的查询维度（"我的买入"≠"我的卖出"），
--   WHERE buyer_id=? 和 WHERE seller_id=? 是互斥的查询条件，放在一个联合索引
--   中无法同时服务两种查询（最左前缀决定了索引必须从最左列开始匹配）。
-- ========================================================================
ALTER TABLE `order` ADD INDEX idx_sellerid_createtime (seller_id, create_time);