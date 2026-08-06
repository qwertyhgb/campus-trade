package com.ming.campustrade.common.constant;

/**
 * Redis 常量类 —— 集中管理项目中所有 Redis 相关的 Key 前缀和过期时间
 *
 * 设计目的：
 * 1. 避免在代码中到处写字符串字面量（魔法值），防止拼写错误导致 Bug
 * 2. 统一管理 Redis Key 的命名规范，方便维护和排查问题
 * 3. 修改过期时间等配置时只需改这一处
 *
 * 为什么类是 final 的？
 * 常量类不应该被继承，final 修饰可以防止子类化，明确表达"这是一个纯工具类"的语义。
 *
 * 为什么构造函数是 private 的？
 * 常量类只包含静态常量，不需要也不应该被实例化。
 * 私有构造函数可以防止外部通过 new RedisConstants() 创建无意义的对象。
 */
public final class RedisConstants {

    /**
     * 私有构造函数：防止外部实例化
     * 如果有人尝试 new RedisConstants()，编译器会直接报错
     */
    private RedisConstants() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    /**
     * 登录用户信息的 Redis Key 前缀
     *
     * 完整的 Key 格式：login:user:{token}
     * 例如：login:user:abc123-def456-ghi789
     *
     * 使用方式：
     * String redisKey = RedisConstants.LOGIN_USER_KEY + ":" + token;
     *
     * 存储内容：
     * 以 Hash 结构存储用户登录信息（UserDTO 的各个字段），
     * 这样每次请求只需根据 token 从 Redis 中取出用户信息，无需查数据库。
     *
     * 为什么用 "login:user" 作为前缀？
     * 1. 冒号分隔是 Redis 社区的命名惯例，表示层级关系（类似文件路径）
     * 2. 在 Redis 可视化工具（如 Another Redis Desktop Manager）中，
     *    相同前缀的 Key 会自动归为一组，方便管理和查看
     */
    public static final String LOGIN_USER_KEY = "login:user";

    /**
     * 登录用户信息的过期时间（单位：分钟）
     *
     * 值为 30，表示用户登录后，其登录信息在 Redis 中最多保存 30 分钟。
     * 超过 30 分钟没有任何操作，Redis 会自动删除该 Key，用户需要重新登录。
     *
     * 滑动过期机制（Sliding Expiration）：
     * 并不是登录后固定 30 分钟就过期，而是每次用户发起请求时，
     * 过滤器会重新刷新这个 Key 的过期时间（重置为 30 分钟）。
     * 效果：只要用户持续操作，登录状态就不会过期；一旦停止操作超过 30 分钟，才会过期。
     * 这比固定过期时间更友好，避免了"用户正在操作却突然被踢出登录"的体验问题。
     *
     * 为什么选择 30 分钟？
     * 这是校园二手交易平台的合理时长：
     * - 太短（如 5 分钟）：用户浏览商品时频繁被要求重新登录，体验差
     * - 太长（如 24 小时）：在公共电脑上登录后，长时间不退出有安全风险
     * - 30 分钟是安全性和易用性的平衡点
     */
    public static final Long LOGIN_USER_TTL = 30L;

    /**
     * 用户 Token 反向索引的 Redis Key 前缀
     *
     * <p>完整 Key 格式：login:user_tokens:{userId}
     * 例如：login:user_tokens:1001</p>
     *
     * <p>存储结构：Redis Set，成员是该用户当前所有有效的 token（原始 UUID，不含前缀）。</p>
     *
     * <p><b>为什么需要这个反向索引？</b><br>
     * login:user{token} 是“token → 用户信息”的正向映射，只能根据 token 查用户。
     * 但封禁用户时需要“根据 userId 找到他所有的 token 并全部删除”（强制下线），
     * 没有反向索引就只能用 SCAN 扫描所有 key（性能差、生产环境禁用）。
     * 维护一个 userId → tokens 的 Set，封禁时一次 SMEMBERS 即可拿到全部 token。</p>
     */
    public static final String LOGIN_USER_TOKENS_KEY = "login:user_tokens:";

    /**
     * 用户封禁标记的 Redis Key 前缀
     *
     * <p>完整 Key 格式：login:disabled:{userId}
     * 例如：login:disabled:1001</p>
     *
     * <p><b>为什么需要这个标记？</b><br>
     * token Hash 里的 status 是登录时的快照，封禁后可能仍是旧值 1（尤其是
     * token 集合先过期、强制下线未生效的情况）。仅靠 Hash 里的 status 判断不可靠。
     * 封禁时实时写入 login:disabled:{userId} 标记，过滤器优先检查它，
     * 能立即拦住被封禁的用户；解封时删除该标记。</p>
     */
    public static final String LOGIN_DISABLED_KEY = "login:disabled:";

    // ==================== 商品详情缓存 ====================

    /**
     * 商品详情缓存的 Redis Key 前缀
     *
     * 完整 Key 格式：product:detail:{id}
     * 例如：product:detail:101
     *
     * 存储内容：ProductVO 的 JSON 字符串
     * 使用 StringRedisTemplate.opsForValue() 存取
     */
    public static final String PRODUCT_DETAIL_KEY = "product:detail:";

    /**
     * 商品详情缓存的基础过期时间（单位：分钟）
     *
     * 实际过期时间 = 基础时间 + 随机偏移（0~10分钟）
     * 加随机偏移是为了防止缓存雪崩（大量缓存同时过期）
     */
    public static final Long PRODUCT_DETAIL_TTL = 30L;

    /**
     * 缓存空值标记（防缓存穿透）
     *
     * 当商品在 MySQL 中不存在时，在 Redis 中存入这个标记字符串，
     * 下次再查同一个 ID 时，看到 "NULL" 就知道商品不存在，直接返回错误，不再查 MySQL。
     */
    public static final String PRODUCT_NULL_VALUE = "NULL";

    /**
     * 空值缓存的过期时间（单位：分钟）
     *
     * 比正常缓存短得多（5分钟 vs 30分钟），因为：
     * 1. 不存在的商品不需要缓存太久
     * 2. 如果商品后来被创建了，5分钟后缓存过期就能查到
     */
    public static final Long PRODUCT_NULL_TTL = 5L;

    // ==================== 活动缓存 ====================

    /**
     * 活动缓存边界：
     * Redis 只用于加速读取活动详情和展示热门排行；MySQL 才是活动、预约和候补数据的最终依据。
     * Redis 缓存中的 currentCount、候补人数可能短暂旧，但绝不能用于决定“用户能否预约”。
     * 是否能预约、是否满员、候补顺序、活动状态转换，仍必须走 MySQL 查询与条件更新。
     *
     * 缓存失效清单（Cache-Aside 的“写后删缓存”）：
     * - 活动创建后：通常无需删旧缓存，因为还没有旧 Key。
     * - 活动编辑、审核、下架、删除后：删除对应活动详情缓存。
     * - 预约成功、取消预约后：删除对应活动详情缓存。
     * - 加入候补、取消候补、候补补位后：删除对应活动详情缓存。
     * - Redis 删除失败：只能记日志，不能让 MySQL 核心事务失败。
     * 先保证 MySQL 写入成功，再删除缓存；下次读取时自然重建新缓存。
     */

    /** 活动详情缓存的 Redis Key 前缀，例如：activity:detail:1001 */
    public static final String ACTIVITY_DETAIL_KEY = "activity:detail:";

    /** 活动详情缓存的基础过期时间（单位：分钟） */
    public static final Long ACTIVITY_DETAIL_TTL = 30L;

    /** 活动详情缓存的随机过期时间范围，实际 TTL 为 30 + 0~10 分钟 */
    public static final int ACTIVITY_DETAIL_TTL_RANDOM_RANGE = 10;

    /** 活动不存在时写入的空值标记，用于防止缓存穿透 */
    public static final String ACTIVITY_NULL_VALUE = "NULL";

    /** 活动空值缓存的过期时间（单位：分钟） */
    public static final Long ACTIVITY_NULL_TTL = 5L;

    /** Redis ZSet 热门活动排行榜 Key */
    public static final String ACTIVITY_HOT_RANK_KEY = "activity:rank:hot";

    /**
     * 热门排行榜默认返回条数。
     *
     * <p>调用方未传 limit 或传了非法值（小于 1）时，排行榜默认返回前 10 个活动 ID。</p>
     */
    public static final int ACTIVITY_HOT_RANK_DEFAULT_LIMIT = 10;

    /**
     * 热门排行榜单次查询的最大条数上限。
     *
     * <p>防止调用方传入超大 limit（如 100000）导致 ZREVRANGE 一次取出海量成员，
     * 占用 Redis 内存与网络带宽。超过上限时按上限截断。</p>
     */
    public static final int ACTIVITY_HOT_RANK_MAX_LIMIT = 50;

    /** 单次预约成功累计的热度分数（ZSet score 增量） */
    public static final double ACTIVITY_HOT_RESERVE_SCORE = 10.0;

    /** 单次加入候补成功累计的热度分数（ZSet score 增量） */
    public static final double ACTIVITY_HOT_WAITLIST_SCORE = 5.0;

    // ==================== 幂等 Token ====================

    /**
     * 幂等 Token 的 Redis Key 前缀。
     *
     * <p>完整 Key 格式：idempotency:token:{userId}:{scene}:{token}
     * 例如：idempotency:token:1001:activity:create:3f2a9c8e-...</p>
     *
     * <p><b>为什么 Key 里要带 userId 和 scene？</b><br>
     * ① 带 userId：A 用户领取的 Token 不能被 B 用户使用（防串用）；<br>
     * ② 带 scene：同一用户在不同业务场景（创建活动/预约/候补）各有独立 Token，互不干扰。</p>
     */
    public static final String IDEMPOTENCY_TOKEN_KEY = "idempotency:token:";

    /**
     * 幂等 Token 的过期时间（单位：分钟）。
     *
     * <p>5 分钟：足够前端完成一次表单填写与提交；超时后 Token 自动失效，
     * 前端需要重新领取 —— 过期失效是幂等保护的兜底，防止 Token 永久占用。</p>
     */
    public static final Long IDEMPOTENCY_TOKEN_TTL = 5L;

    // ==================== 限流 ====================

    /**
     * 限流计数器的 Redis Key 前缀。
     *
     * <p>完整 Key 格式：rate:limit:{scene}:{clientKey}
     * 例如：rate:limit:login:192.168.1.100、rate:limit:activity:query:192.168.1.100</p>
     *
     * <p><b>为什么 Key 里要带场景和客户端标识？</b><br>
     * ① 带 scene：登录限流和活动查询限流互不影响（各计各的数）；<br>
     * ② 带 clientKey：不同 IP 各自计数，一个人被限流不会连累其他人。</p>
     */
    public static final String RATE_LIMIT_KEY = "rate:limit:";

    /**
     * 限流时间窗口长度（单位：秒）。
     *
     * <p>固定时间窗口：Key 第一次计数时设置 60 秒过期，60 秒后 Key 自动消失、
     * 计数归零 —— 即“每分钟最多 N 次”。</p>
     */
    public static final Long RATE_LIMIT_WINDOW_SECONDS = 60L;

    /** 登录接口限流上限：每 IP 每分钟最多 10 次（防暴力破解/撞库） */
    public static final int RATE_LIMIT_LOGIN_MAX = 10;

    /** 活动公开查询限流上限：每 IP 每分钟最多 60 次（防爬虫高频抓取） */
    public static final int RATE_LIMIT_QUERY_MAX = 60;
}
