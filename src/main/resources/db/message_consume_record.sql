-- 消息消费记录表（RabbitMQ 消费幂等）
-- 执行前提：无（独立表）
-- 可重复执行，不会报错（所有索引均内联在 CREATE TABLE IF NOT EXISTS 中）
--
-- 设计说明：RabbitMQ 是 at-least-once 投递模型，网络抖动、消费超时重投、消费者重启
--   都可能导致同一条消息被消费多次。本表通过 event_id 唯一索引实现消费幂等：
--   消费者处理消息前先 INSERT（或查询）本表，重复消息会触发唯一键冲突而被跳过。
--   event_id 由生产者生成（UUID），一条业务事件对应一条记录，全局唯一。

USE campus_trade;

-- ============================================================
-- 消息消费记录表
-- ============================================================
CREATE TABLE IF NOT EXISTS message_consume_record (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '主键',

    -- 事件唯一ID（由生产者生成，全局唯一）
    event_id VARCHAR(64) NOT NULL COMMENT '事件唯一ID（UUID）',

    -- 消费的队列名称（方便排查问题）
    queue_name VARCHAR(100) NOT NULL COMMENT '队列名称',

    -- 消费状态：1成功 2失败
    consume_status TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '消费状态：1成功 2失败',

    -- 失败时的错误信息
    error_msg VARCHAR(500) COMMENT '失败原因',

    consume_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消费时间',

    -- 核心唯一索引：同一个事件只能被消费一次（消费幂等的数据库保证）
    UNIQUE KEY uk_event_id (event_id),

    -- 索引1：按队列排查消费记录（如排查某队列消费失败的消息）
    -- SQL：WHERE queue_name = ? ORDER BY consume_time DESC
    -- 最左前缀：
    --   ✅ WHERE queue_name=? ORDER BY consume_time → 完全命中
    --   ✅ WHERE queue_name=?                       → 命中最左列
    --   ❌ ORDER BY consume_time                    → 未从 queue_name 开始
    INDEX idx_queue_time (queue_name, consume_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='消息消费幂等记录表';
