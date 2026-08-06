package com.ming.campustrade.service;

import java.util.List;

import com.ming.campustrade.common.constant.RateLimitScene;
import com.ming.campustrade.common.constant.RedisConstants;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 限流服务 —— 基于 Redis 原子计数实现“固定时间窗口”限流。
 *
 * <p><b>固定时间窗口原理：</b><br>
 * 每个“场景 + 客户端标识”对应一个 Redis Key，Key 的值是窗口内的请求计数：
 * 第一次请求时计数=1 并给 Key 设置 60 秒过期；之后每次请求计数 +1；
 * 60 秒后 Key 自动过期、计数归零 —— 效果就是“每分钟最多 N 次”。
 * 计数超过该场景上限时拒绝访问，未超过则放行。</p>
 *
 * <p><b>为什么必须用 Lua 脚本做“自增 + 首次设置过期”？</b><br>
 * 如果用两条独立命令（先 INCR 再 EXPIRE），真正的风险是：INCR 执行后
 * 应用恰好异常退出，来不及执行 EXPIRE —— 这个 Key 就永远不会过期，
 * 计数会无限增长，限流变成永久封禁。Lua 脚本在 Redis 内部<b>原子</b>执行
 * 两条命令，要么都执行、要么都不执行，不存在这个窗口 —— 这是本模块的核心正确性保证。</p>
 *
 * <p><b>为什么限流故障要降级放行，而幂等 Token 故障要拒绝业务？</b><br>
 * 两者保护的目标不同：幂等 Token 保护<b>数据正确性</b>（防重复提交，Redis 挂了
 * 还放行会导致重复订单），所以必须拒绝；限流保护<b>系统负载</b>（防刷接口，
 * 即便限流失效也只是多扛一点流量），所以 Redis 挂了应该放行，
 * 不能因为“无法限流”就把所有正常用户拒之门外。</p>
 *
 * <p><b>固定窗口的局限：</b>窗口边界（如第 59 秒和第 61 秒）可能短时间放行
 * 较多请求，触发“边界双倍放行”。实现简单但不够平滑；后续可学习
 * 滑动窗口或令牌桶算法来消除边界问题。</p>
 *
 * @author ming
 */
@Slf4j
@Service
public class RateLimitService {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 构造器注入 Redis 字符串操作模板（Lua 脚本通过 execute 执行）。
     *
     * @param stringRedisTemplate Redis 字符串操作模板
     */
    public RateLimitService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * Lua 脚本：对 Key 执行 INCR；返回值等于 1（首次计数）时设置过期时间；返回本次计数。
     *
     * <p>KEYS[1] = 限流计数 Key，ARGV[1] = 时间窗口秒数。
     * 原子性的意义：避免“INCR 后应用异常退出、来不及 EXPIRE”导致 Key 永不过期
     * （计数无限增长 = 永久封禁）。</p>
     */
    private static final DefaultRedisScript<Long> INCR_AND_EXPIRE_SCRIPT = new DefaultRedisScript<>();

    static {
        INCR_AND_EXPIRE_SCRIPT.setScriptText(
                "local count = redis.call('INCR', KEYS[1])\n"
                        + "if count == 1 then\n"
                        + "    redis.call('EXPIRE', KEYS[1], ARGV[1])\n"
                        + "end\n"
                        + "return count");
        // 脚本返回值是 Redis 整数（Long），指定结果类型供 Spring 反序列化
        INCR_AND_EXPIRE_SCRIPT.setResultType(Long.class);
    }

    /**
     * 判断本次请求是否允许通过（每调用一次计数 +1）。
     *
     * <p>返回规则：</p>
     * <ul>
     *   <li>计数 ≤ 该场景上限：返回 true（放行）；</li>
     *   <li>计数 &gt; 上限：返回 false（限流拒绝）；</li>
     *   <li>Redis 异常：记录警告日志后返回 true（限流故障降级放行，
     *       不能因为限流组件挂了就把所有正常请求拒之门外）。</li>
     * </ul>
     *
     * <p><b>注意：</b>本方法有副作用 —— 每次调用都会让计数 +1，
     * 因此<b>每个请求只能调用一次</b>，重复调用会重复计数、提前触发限流。</p>
     *
     * @param scene     限流场景（枚举固定值，禁止调用处自行拼接字符串）
     * @param clientKey 客户端标识（下一步接入时会传 IP 地址）
     * @return true=放行；false=已超限，拒绝本次请求
     */
    public boolean isAllowed(RateLimitScene scene, String clientKey) {
        // 防御性校验：场景或客户端标识缺失时无法精确计数，
        // 直接放行（与“限流故障降级放行”同一原则，不误伤正常请求）
        if (scene == null || clientKey == null || clientKey.isBlank()) {
            log.warn("限流参数缺失，降级放行：scene={}, clientKey={}", scene, clientKey);
            return true;
        }

        // Key = 场景 + 客户端标识：登录限流与活动查询限流互不影响，不同 IP 各计各的数
        String key = RedisConstants.RATE_LIMIT_KEY + scene.getValue() + ":" + clientKey;
        try {
            Long count = stringRedisTemplate.execute(
                    INCR_AND_EXPIRE_SCRIPT,
                    List.of(key),
                    String.valueOf(RedisConstants.RATE_LIMIT_WINDOW_SECONDS));
            long current = count == null ? 0 : count;
            boolean allowed = current <= scene.getMaxTimes();
            if (!allowed) {
                log.warn("限流触发：scene={}, clientKey={}, 当前计数={}, 上限={}",
                        scene.getValue(), clientKey, current, scene.getMaxTimes());
            }
            return allowed;
        } catch (Exception e) {
            // 限流是保护系统负载的辅助能力：Redis 故障时降级放行，
            // 宁可多扛一点流量，也不能把正常用户全部拒之门外
            log.warn("限流计数失败（降级放行）：scene={}, clientKey={}", scene.getValue(), clientKey, e);
            return true;
        }
    }
}
