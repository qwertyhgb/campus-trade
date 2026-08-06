package com.ming.campustrade.common.constant;

/**
 * 限流场景枚举 —— 限定哪些接口参与限流，以及各自的上限。
 *
 * <p><b>为什么用枚举而不是在调用处随意拼接字符串？</b><br>
 * 限流 Key 是 rate:limit:{scene}:{clientKey}，如果每个调用处自己拼场景字符串，
 * 很容易出现“登录过滤器和限流校验写的场景名不一致”导致限流失效或互相干扰。
 * 用枚举把场景值固定下来，登录限流、活动查询限流都引用同一个枚举，天然一致。</p>
 *
 * <p><b>为什么把最大次数也放进枚举？</b><br>
 * 每个场景的限流上限是它的固有属性（登录接口 10 次/分钟、查询接口 60 次/分钟），
 * 绑定在枚举上后，调用处只需要传场景，无需再传上限 —— 上限集中管理，不会传错。</p>
 *
 * @author ming
 */
public enum RateLimitScene {

    /**
     * 登录接口限流：每 IP 每分钟最多 10 次。
     *
     * <p>登录是账号安全的第一道门：防止暴力破解、撞库等高频尝试，
     * 上限设得比较严（10 次/分钟），正常用户输错几次密码后稍等即可重试。</p>
     */
    LOGIN("login", RedisConstants.RATE_LIMIT_LOGIN_MAX),

    /**
     * 活动公开查询限流：每 IP 每分钟最多 60 次。
     *
     * <p>活动列表/详情是公开接口，容易被爬虫高频抓取；
     * 上限放宽到 60 次/分钟（平均每秒 1 次），正常浏览完全够用，爬虫则会被拦截。</p>
     */
    ACTIVITY_QUERY("activity:query", RedisConstants.RATE_LIMIT_QUERY_MAX);

    /** 场景的固定字符串值（直接拼进 Redis Key，禁止调用处自行拼接） */
    private final String value;

    /** 该场景在时间窗口内的最大允许次数 */
    private final int maxTimes;

    /**
     * 枚举构造器（Java 枚举的构造函数只能是 private 的）。
     *
     * @param value    场景的固定字符串值
     * @param maxTimes 时间窗口内最大允许次数
     */
    RateLimitScene(String value, int maxTimes) {
        this.value = value;
        this.maxTimes = maxTimes;
    }

    /**
     * 获取场景的固定字符串值。
     *
     * @return 场景值，如 "login"、"activity:query"
     */
    public String getValue() {
        return value;
    }

    /**
     * 获取该场景的限流上限。
     *
     * @return 时间窗口内最大允许次数
     */
    public int getMaxTimes() {
        return maxTimes;
    }
}
