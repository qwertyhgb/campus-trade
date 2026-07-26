USE campus_trade;

-- ========================================================================
-- 商品表（product）
-- ========================================================================
-- 索引设计说明：
--
-- 1. 为什么需要索引？
--    商品表是首页流量入口，用户访问首页、分类筛选、搜索时都会查询它。
--    校园平台二手商品数量虽然远不如电商平台，但高频的查询（每个用户
--    打开 App 都会触发）要求查询必须极快。没有索引时，每次查询都要
--    全表扫描，随着商品积累（数万到数十万）响应时间会从毫秒级变成秒级。
--
-- 2. 为什么单列索引不够？
--    商品业务的核心查询是：
--      SELECT * FROM product WHERE status=1 ORDER BY create_time DESC
--    如果只有 idx_status 单列索引，MySQL 可以通过 status=1 快速定位
--    到在售商品，但 ORDER BY create_time 仍需 filesort。如果海量商品
--    都是 status=1（大部分活跃商品都在售），idx_status 的过滤效果很差，
--    可能扫到几十万行再做排序，性能极差。
--
-- 3. 联合索引如何解决？
--    联合索引 idx_status_createtime(status, create_time) 把筛选列放在
--    前面、排序列放在后面。B+Tree 先按 status 排序，在 status 相同
--    （都是 1）的情况下按 create_time 排序。这样 WHERE status=1 能
--    快速定位，ORDER BY create_time 也能直接用索引顺序，一举两得。
--
-- 4. 最左前缀原则（回顾）：
--    联合索引 (a, b, c)：
--      ✅ WHERE a=1                   → 能用
--      ✅ WHERE a=1 AND b=2           → 能用
--      ✅ WHERE a=1 AND b=2 AND c=3   → 能用
--      ✅ WHERE a=1 ORDER BY b        → 能用（a 过滤，b 排序）
--      ❌ WHERE b=2                   → 不能（没从 a 开始）
--      ❌ WHERE c=3                   → 不能（没从 a 开始）
--      ❌ WHERE a=1 AND c=3           → a 能用索引，c 不能用（跳过了 b）
--
--    设计联合索引时的决策依据：
--    - 等值条件（=）的列放在最前面
--    - 范围条件（>, <, BETWEEN）的列放在中间
--    - ORDER BY / GROUP BY 的列放在最后面
--    这样才能最大化索引利用率，一个索引同时覆盖 WHERE 过滤和排序。
-- ========================================================================

CREATE TABLE product (
                         id              BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '商品ID',
                         title           VARCHAR(100) NOT NULL COMMENT '商品标题',
                         description     TEXT COMMENT '商品描述',
                         price           DECIMAL(10,2) NOT NULL COMMENT '售价',
                         original_price  DECIMAL(10,2) COMMENT '原价',
                         image           VARCHAR(255) COMMENT '商品封面图URL',
                         category_id     BIGINT UNSIGNED COMMENT '分类ID（后续分类模块使用）',
                         seller_id       BIGINT UNSIGNED NOT NULL COMMENT '卖家用户ID',
                         condition_level TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '成色：0全新 1几乎全新 2轻微使用痕迹 3明显使用痕迹',
                         status          TINYINT UNSIGNED NOT NULL DEFAULT 4 COMMENT '状态：0下架 1在售 2锁定 3已售 4待审核 5已驳回',
                         view_count      INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '浏览量',
                         review_remark   VARCHAR(255) COMMENT '审核备注（审核驳回原因）',
                         create_time     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                         update_time     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                         deleted         TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',

                         -- 以下单列索引服务于
                         -- ① 辅助复杂查询：当查询条件组合与已有联合索引不匹配时，
                         --    单列索引可能被 MySQL 优化器通过 Index Merge 技术利用
                         -- ② 服务简单查询：selectById 以外的 seller_id 精确查找
                         INDEX idx_seller_id   (seller_id),
                         INDEX idx_category_id (category_id),
                         INDEX idx_status      (status),
                         INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商品表';

-- ========================================================================
-- 联合索引 1：商品首页列表（在售商品按时间排序）
-- ========================================================================
-- SQL 原型：SELECT * FROM product WHERE status=1 ORDER BY create_time DESC
-- 查询频率：极高（所有用户打开首页都会触发，是全站最高频查询）
--
-- 为什么需要 (status, create_time) 而不是 (create_time, status)？
--   最左前缀原则告诉我们，索引的第一列必须能快速过滤掉大量无关数据。
--   status=1 过滤后剩余的在售商品数量远小于 create_time 范围过滤后的量，
--   所以 status 应该放在最左列。如果反过来建 (create_time, status)：
--     ✅ WHERE status=1 → 不能使用索引（没从 create_time 开始）
--     ✅ ORDER BY create_time → 能用，但那是索引扫描而非索引过滤（效率低）
--   (status, create_time) 则完美匹配查询：
--     status=1 做等值过滤走索引，create_time 在 status 内部有序供 ORDER BY 使用
--
-- 最左前缀实践：
--   ✅ WHERE status=1 ORDER BY create_time → 完全命中，无需 filesort
--   ✅ WHERE status=1                      → 命中索引
--   ❌ ORDER BY create_time                → 未从 status 开始，无法使用
-- ========================================================================
ALTER TABLE product ADD INDEX idx_status_createtime (status, create_time);

-- ========================================================================
-- 联合索引 2：按分类筛选在售商品
-- ========================================================================
-- SQL 原型：SELECT * FROM product WHERE status=1 AND category_id=? ORDER BY create_time DESC
-- 查询频率：较高（用户选择某个分类后触发）
--
-- 为什么不直接用 idx_status_createtime？
--   idx_status_createtime(status, create_time) 中的第二列是 create_time，
--   但 WHERE 条件中的第二列是 category_id。根据最左前缀原则，status=1
--   能走索引，但 category_id=? 的过滤无法利用索引，只能在 status=1 的结果
--   中逐行扫描匹配 category_id，浪费了大量 IO。
--
-- 为什么是 (category_id, status) 而不是 (status, category_id)？
--   这里 category_id 的过滤效果比 status 更强（几十个分类 vs 只在售/下架两种状态），
--   把选择性更高的 category_id 放前面能更快缩小范围。同时由于 status 是等值条件
--   放在第二列也能走索引，而且后续 ORDER BY create_time 虽然不能直接利用索引排序
--   （因为 category_id 是范围扫描），但已有 idx_status_createtime 兜底处理纯 status
--   排序场景。这就形成了一个互补的索引体系。
--
-- 最左前缀实践：
--   ✅ WHERE category_id=? AND status=?   → 完全命中
--   ✅ WHERE category_id=?                → 命中索引（只用最左列）
--   ❌ WHERE status=1                     → 未从 category_id 开始，无法使用
--     （这种情况由 idx_status_createtime 处理，两个索引分工明确）
-- ========================================================================
ALTER TABLE product ADD INDEX idx_categoryid_status (category_id, status);

-- ========================================================================
-- 联合索引 3：我发布的商品列表
-- ========================================================================
-- SQL 原型：SELECT * FROM product WHERE seller_id=? ORDER BY create_time DESC
-- 查询频率：较高（卖家每次打开"我的商品"页面都会触发）
--
-- 思路与 order 表的 idx_buyerid_createtime 完全一致：
-- (seller_id, create_time) 联合覆盖 WHERE 过滤和 ORDER BY 排序。
-- 注意这里没有将 status 也加入索引，因为卖家通常要看到自己所有商品
-- （包含下架、在售、已售），不需要按 status 过滤，加入反而增加索引体积。
-- 如果后续需要"卖家查看在售商品"，可考虑 (seller_id, status, create_time) 三列联合。
-- ========================================================================
ALTER TABLE product ADD INDEX idx_sellerid_createtime (seller_id, create_time);