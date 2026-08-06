-- 操作审计日志表（阶段 8：日志与审计）
-- 执行前提：user 表已存在（见 user.sql）
-- 首次建表可直接执行。若 operation_log 已存在，新增索引不会由 CREATE TABLE IF NOT EXISTS 自动补上，
-- 请在发布窗口手工执行本文件末尾给出的 ALTER TABLE（只需执行一次）。
--
-- 设计说明：
-- 1. 不设 deleted 字段：审计日志是“只能追加、不能删除”的证据链，
--    即使管理员操作也需要留痕，逻辑删除会破坏审计完整性。
-- 2. detail 字段必须脱敏后写入：密码、完整 Token、手机号、身份证号等
--    敏感信息一律不允许进入日志表（见切面与记录代码的脱敏规则）。
-- 3. trace_id 用于把一条操作日志与后端日志文件中的请求链路串起来
--    （MDC 中同名的 traceId），排查问题时能“日志表 + 日志文件”双向定位。

USE campus_trade;

-- ============================================================
-- 操作审计日志表
-- ============================================================
CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '日志ID',

    -- 谁操作的：操作人用户ID（未登录的系统动作如定时任务可为 NULL）
    operator_id BIGINT UNSIGNED COMMENT '操作人用户ID（系统动作可为空）',

    -- 做了什么：动作编码，如 ACTIVITY_REVIEW / ACTIVITY_OFF_SHELF / USER_BAN / RESERVATION_CREATE
    action VARCHAR(50) NOT NULL COMMENT '操作动作编码',

    -- 操作对象：目标类型（activity/user/reservation/waitlist/product/order）与目标业务ID
    target_type VARCHAR(30) COMMENT '目标类型',
    target_id BIGINT UNSIGNED COMMENT '目标业务ID',

    -- 操作详情：状态流转等业务摘要，必须已脱敏
    detail VARCHAR(500) COMMENT '操作详情（脱敏后）',

    -- 操作结果：1成功 0失败（失败时记录异常摘要，便于审计追责）
    success TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '操作结果：1成功 0失败',
    error_msg VARCHAR(500) COMMENT '失败时的异常摘要',

    -- 来源与链路：操作IP（TCP对端地址，不信任可伪造的 X-Forwarded-For）+ 请求追踪ID
    ip VARCHAR(45) COMMENT '操作来源IP',
    trace_id VARCHAR(64) COMMENT '请求追踪ID（与日志文件中的 traceId 对应）',

    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',

    -- 索引1：按操作人查历史（审计追责常用）
    -- SQL：WHERE operator_id = ? ORDER BY create_time DESC
    -- 最左前缀：WHERE operator_id=? → 命中；ORDER BY create_time 跟随索引有序，无需 filesort
    INDEX idx_operator_create (operator_id, create_time),

    -- 索引2：按操作对象查历史（例如查某个活动的全部审核/下架记录）
    -- SQL：WHERE target_type = ? AND target_id = ?
    -- 最左前缀：target_type 在前，保证类型+ID 都能命中
    INDEX idx_target (target_type, target_id),

    -- 索引3：管理员全量分页按时间倒序查审计日志。
    -- id 作为第二排序字段，既与 Service 的稳定排序一致，也能区分同一秒写入的多条日志。
    INDEX idx_create_id (create_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='操作审计日志表';

-- ============================================================
-- 已存在 operation_log 表的升级说明（仅在 idx_create_id 尚不存在时执行一次）
-- ALTER TABLE operation_log ADD INDEX idx_create_id (create_time, id);
-- ============================================================
