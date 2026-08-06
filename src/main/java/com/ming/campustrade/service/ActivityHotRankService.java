package com.ming.campustrade.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.ming.campustrade.common.constant.RedisConstants;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 活动热门排行榜服务 —— 用 Redis ZSet 保存“活动 ID → 热度分数”。
 *
 * <p><b>ZSet 结构怎么理解？</b><br>
 * 一个 ZSet（有序集合）由若干“成员 + 分数”组成：本模块里
 * <b>member = 活动 ID 的字符串</b>，<b>score = 该活动的热度分数</b>。
 * 同一个活动多次加分不会在集合里重复出现，只会把它的 score 累加 ——
 * 这正好对应“同一活动被预约/候补多次，热度越来越高”的业务语义，
 * 而且排序（按分数）由 Redis 内部完成，查询排行榜无需自己排序。</p>
 *
 * <p><b>热度数据的定位（重要）：</b><br>
 * 排行榜只属于<b>展示数据</b>：Redis 丢失后可以重新积累，或后续从 MySQL 重建。
 * 热度分数<b>绝不能</b>参与预约名额判断、候补排序或活动状态流转 ——
 * 这些核心数据必须以 MySQL 为准，Redis 只是加速展示。</p>
 *
 * <p><b>Redis 是可选加速层：</b>所有 Redis 异常都在本组件内记录警告并降级，
 * 不影响任何 MySQL 核心业务（预约、候补、状态机照常运行）。</p>
 *
 * @author ming
 */
@Slf4j
@Service
public class ActivityHotRankService {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 构造器注入 Redis 字符串操作模板（ZSet 操作通过 opsForZSet() 获取）。
     *
     * @param stringRedisTemplate Redis 字符串操作模板
     */
    public ActivityHotRankService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 记录热度增加：给指定活动累加热度分数。
     *
     * <p>对应 Redis 命令 ZINCRBY activity:rank:hot {score} {activityId}：
     * 成员不存在时自动创建，已存在时在原分数上累加 —— 所以同一活动
     * 被多次加分不会重复出现，只会越来越高，天然适合“热门榜”。</p>
     *
     * <p><b>为什么 ZSet 成员要统一用 String.valueOf(activityId)？</b><br>
     * Redis 的 member 本质是字符串；如果有的地方存 "1001"、有的地方存 "1001 "，
     * 会被当成两个不同的活动。统一格式保证同一活动只对应一个成员。</p>
     *
     * <p>参数非法（空、ID 非正、分数非正数）时直接返回，不抛异常；
     * Redis 出错只记录警告日志，热度只是展示数据，绝不影响预约/候补主流程。</p>
     *
     * @param activityId 活动 ID
     * @param score      本次累计的热度分数（必须为正数，如预约 +10、候补 +5）
     */
    public void recordHeat(Long activityId, double score) {
        // 防御性校验：调用方漏传/传错参数时静默忽略，避免把脏数据写进排行榜
        if (activityId == null || activityId <= 0 || score <= 0) {
            log.warn("热度记录参数不合法，已忽略：activityId={}, score={}", activityId, score);
            return;
        }
        try {
            stringRedisTemplate.opsForZSet().incrementScore(
                    RedisConstants.ACTIVITY_HOT_RANK_KEY,
                    String.valueOf(activityId),
                    score);
        } catch (Exception e) {
            // 排行榜是展示数据：写失败只降级（该活动暂时不上榜），不影响业务
            log.warn("热度记录写入失败（不影响业务）：activityId={}, score={}", activityId, score, e);
        }
    }

    /**
     * 查询热门活动 ID 列表，按分数从高到低返回前 N 个。
     *
     * <p>对应 Redis 命令 ZREVRANGE activity:rank:hot 0 (limit-1)：
     * ZSet 本身按分数升序存储，reverseRange 倒过来取就是“热度从高到低”。</p>
     *
     * <p><b>为什么限流（limit 保护）？</b><br>
     * 榜单只需要展示前几名，防止调用方传超大 limit 一次取出海量成员，
     * 浪费 Redis 内存与网络带宽：小于 1 用默认 10，超过 50 按 50 截断。</p>
     *
     * <p>Redis 不可用时返回空列表，让上层接口降级为“暂无热门榜”，
     * 不影响活动主业务；个别无法解析为 Long 的脏成员会被跳过并记录警告，
     * 不让一条脏数据拖垮整个榜单。</p>
     *
     * @param limit 期望返回条数（自动保护：小于 1 用默认 10，最大 50）
     * @return 热门活动 ID 列表（按热度降序）；Redis 不可用时返回空列表
     */
    public List<Long> getHotActivityIds(Integer limit) {
        // limit 保护：非法值回落默认，超大值截断，保证 ZREVRANGE 范围安全
        int safeLimit = normalizeLimit(limit);
        try {
            Set<String> members = stringRedisTemplate.opsForZSet()
                    .reverseRange(RedisConstants.ACTIVITY_HOT_RANK_KEY, 0, safeLimit - 1);
            if (members == null || members.isEmpty()) {
                return List.of();
            }

            // 把字符串成员安全转回 Long；ZSet 理论上只存合法 ID，
            // 但历史脏数据或外部写入可能破坏格式，逐条转换并跳过异常值。
            List<Long> activityIds = new ArrayList<>(members.size());
            for (String member : members) {
                try {
                    activityIds.add(Long.valueOf(member));
                } catch (NumberFormatException e) {
                    log.warn("热门榜存在无法解析的活动 ID 成员，已跳过：member={}", member);
                }
            }
            return activityIds;
        } catch (Exception e) {
            // 排行榜是展示数据：查询失败降级为空列表，不影响活动主业务
            log.warn("热门榜查询失败（降级为空列表）：limit={}", limit, e);
            return List.of();
        }
    }

    /**
     * 删除某活动的热度记录（活动删除/下架时清理排行榜残留）。
     *
     * <p>对应 Redis 命令 ZREM activity:rank:hot {activityId}。
     * 重复删除也安全：成员不存在时 ZREM 返回 0，不会报错 ——
     * 幂等操作，调用方无需关心该活动是否在榜。</p>
     *
     * @param activityId 活动 ID
     */
    public void removeActivity(Long activityId) {
        if (activityId == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForZSet()
                    .remove(RedisConstants.ACTIVITY_HOT_RANK_KEY, String.valueOf(activityId));
        } catch (Exception e) {
            // 清理失败会留下排行榜残留；当前排行榜没有 TTL，残留不会自动过期，
            // 后续通过 MySQL 过滤无效活动或重建排行榜来处理即可
            log.warn("删除活动热度记录失败（不影响业务）：activityId={}", activityId, e);
        }
    }

    /**
     * 把调用方传入的 limit 规范化为安全值。
     *
     * @param limit 调用方传入的条数（可能为 null / 非法值）
     * @return 规范化后的条数：小于 1 用默认 10，超过最大上限 50 截断
     */
    private int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return RedisConstants.ACTIVITY_HOT_RANK_DEFAULT_LIMIT;
        }
        return Math.min(limit, RedisConstants.ACTIVITY_HOT_RANK_MAX_LIMIT);
    }
}
