package com.ming.campustrade.service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ming.campustrade.common.constant.RedisConstants;
import com.ming.campustrade.vo.ActivityPublicDetailVO;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 活动公开详情缓存组件 —— 只负责公开活动详情 Redis 缓存的读写，不参与业务查询和组装。
 *
 * <p>这个组件只缓存公开活动详情，绝不缓存审核内部信息。活动、预约和候补数据以 MySQL 为最终依据。缓存命中只能用于加速读取，
 * 缓存未命中、空值缓存命中、Redis 故障或 JSON 损坏时，都由上层业务回查 MySQL。</p>
 *
 * <p>Redis 属于可选的加速层：所有 Redis 或 JSON 异常都在本组件内记录警告并降级，
 * 不应影响活动详情查询及其他依赖 MySQL 的核心业务。</p>
 *
 * @author ming
 */
@Slf4j
@Service
public class ActivityCacheService {

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    /**
     * 构造器注入 Redis 和 JSON 处理依赖。
     *
     * @param stringRedisTemplate Redis 字符串操作模板
     * @param objectMapper       JSON 序列化与反序列化工具
     */
    public ActivityCacheService(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 尝试读取并反序列化活动详情缓存。
     *
     * <p>返回 {@code null} 只表示缓存层没有可用详情，可能是未命中、空值标记、
     * Redis 故障或 JSON 损坏，并不等于活动不存在。上层业务仍需按顺序回查 MySQL。</p>
     *
     * @param activityId 活动 ID
     * @return 缓存中的活动详情，缓存不可用时返回 {@code null}
     */
    public ActivityPublicDetailVO getCachedDetail(Long activityId) {
        if (activityId == null) {
            return null;
        }

        String cacheKey = RedisConstants.ACTIVITY_DETAIL_KEY + activityId;
        String cachedJson;
        try {
            cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            log.warn("读取活动详情缓存失败，降级为查询 MySQL：activityId={}", activityId, e);
            return null;
        }

        if (cachedJson == null || RedisConstants.ACTIVITY_NULL_VALUE.equals(cachedJson)) {
            return null;
        }

        try {
            return objectMapper.readValue(cachedJson, ActivityPublicDetailVO.class);
        } catch (Exception e) {
            log.warn("活动详情缓存 JSON 损坏，将删除缓存并回查 MySQL：activityId={}", activityId, e);
            evict(activityId);
            return null;
        }
    }

    /**
     * 判断活动详情是否命中过活动不存在的空值缓存。
     *
     * @param activityId 活动 ID
     * @return 只有缓存值严格等于空值标记时返回 {@code true}
     */
    public boolean isNullCached(Long activityId) {
        if (activityId == null) {
            return false;
        }

        try {
            String cachedValue = stringRedisTemplate.opsForValue()
                    .get(RedisConstants.ACTIVITY_DETAIL_KEY + activityId);
            return RedisConstants.ACTIVITY_NULL_VALUE.equals(cachedValue);
        } catch (Exception e) {
            log.warn("读取活动空值缓存失败，继续查询 MySQL：activityId={}", activityId, e);
            return false;
        }
    }

    /**
     * 缓存活动详情，TTL 为基础时间加随机分钟数，避免缓存同时失效。
     *
     * @param activityId 活动 ID
     * @param detail     公开活动详情
     */
    public void cacheDetail(Long activityId, ActivityPublicDetailVO detail) {
        if (activityId == null || detail == null) {
            return;
        }

        long randomMinutes = ThreadLocalRandom.current()
                .nextInt(RedisConstants.ACTIVITY_DETAIL_TTL_RANDOM_RANGE + 1);
        long ttlMinutes = RedisConstants.ACTIVITY_DETAIL_TTL + randomMinutes;
        String cacheKey = RedisConstants.ACTIVITY_DETAIL_KEY + activityId;

        try {
            String json = objectMapper.writeValueAsString(detail);
            stringRedisTemplate.opsForValue().set(cacheKey, json, Duration.ofMinutes(ttlMinutes));
            log.debug("活动详情写入缓存：activityId={}, ttl={}分钟", activityId, ttlMinutes);
        } catch (Exception e) {
            log.warn("活动详情缓存写入失败，不影响正常业务：activityId={}", activityId, e);
        }
    }

    /**
     * 缓存活动不存在的空值，防止缓存穿透。
     *
     * @param activityId 活动 ID
     */
    public void cacheNull(Long activityId) {
        if (activityId == null) {
            return;
        }

        String cacheKey = RedisConstants.ACTIVITY_DETAIL_KEY + activityId;
        try {
            stringRedisTemplate.opsForValue().set(
                    cacheKey,
                    RedisConstants.ACTIVITY_NULL_VALUE,
                    Duration.ofMinutes(RedisConstants.ACTIVITY_NULL_TTL)
            );
            log.debug("活动不存在，写入空值缓存：activityId={}", activityId);
        } catch (Exception e) {
            log.warn("活动空值缓存写入失败，不影响正常业务：activityId={}", activityId, e);
        }
    }

    /**
     * 删除指定活动的详情缓存。
     *
     * <p>调用时通常已经完成 MySQL 写入；Redis 删除失败只能记录日志，
     * 不能让活动编辑、预约或候补等核心事务失败。</p>
     *
     * @param activityId 活动 ID
     */
    public void evict(Long activityId) {
        if (activityId == null) {
            return;
        }

        try {
            stringRedisTemplate.delete(RedisConstants.ACTIVITY_DETAIL_KEY + activityId);
            log.debug("已清除活动详情缓存：activityId={}", activityId);
        } catch (Exception e) {
            log.warn("清除活动详情缓存失败，缓存将自然过期：activityId={}", activityId, e);
        }
    }
}
