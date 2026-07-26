package com.ming.campustrade.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.ming.campustrade.common.constant.RedisConstants;

import lombok.extern.slf4j.Slf4j;

/**
 * 商品详情缓存组件 —— 统一管理商品详情缓存的失效
 *
 * <p><b>为什么需要这个组件？</b><br>
 * 商品详情用了 Redis 缓存（Cache-Aside）。任何让商品数据发生变化的操作，
 * 都必须删除对应缓存，否则用户会读到旧数据。除了商品模块自身的编辑/删除/审核，
 * <b>订单流程</b>也会改变商品状态（下单锁定、确认售出、取消释放），
 * <b>封禁卖家</b>还会批量下架商品——这些跨模块操作同样需要清缓存。</p>
 *
 * <p>把"清商品缓存"抽成一个独立组件，让订单、用户等模块都能复用，
 * 避免在每个地方重复写删除逻辑，也防止遗漏（漏清缓存是典型的隐蔽 Bug）。</p>
 *
 * @author ming
 */
@Slf4j
@Service
public class ProductCacheService {

    private final StringRedisTemplate stringRedisTemplate;

    public ProductCacheService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 清除指定商品的详情缓存
     *
     * <p>在商品状态/数据发生任何变化后调用（编辑、删除、审核、下单锁定、
     * 确认售出、取消释放、封禁下架等）。删除后下次查询会从 MySQL 重建缓存。</p>
     *
     * <p><b>为什么用 try-catch 包裹？</b><br>
     * 调用此方法时，数据库的状态变更通常已经成功。Redis 是外部服务，可能暂时不可用。
     * 如果因为删缓存失败就抛异常，会让本来成功的业务操作返回 500。
     * 最坏情况：缓存多活一段时间（最多 30+9 分钟），到期自然失效，不会造成永久不一致。</p>
     *
     * @param productId 要清除缓存的商品 ID
     */
    public void evict(Long productId) {
        try {
            stringRedisTemplate.delete(RedisConstants.PRODUCT_DETAIL_KEY + productId);
            log.debug("已清除商品详情缓存：productId={}", productId);
        } catch (Exception e) {
            log.warn("清除商品详情缓存失败（缓存将自然过期）：productId={}", productId, e);
        }
    }
}
