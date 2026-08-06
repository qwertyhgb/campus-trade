package com.ming.campustrade.service;

import java.time.Duration;
import java.util.UUID;

import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.constant.IdempotencyScene;
import com.ming.campustrade.common.constant.RedisConstants;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.UserVO;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 幂等 Token 服务 —— 为写操作提供“领取 Token + 原子消费 Token”的防重复提交能力。
 *
 * <p><b>为什么能防重复提交？（核心原理）</b><br>
 * 前端提交写请求前先领取一个一次性 Token；后端在处理业务前调用
 * {@link #consumeToken} 执行 Redis 的 DELETE —— 而 DELETE 是<b>原子操作</b>：
 * 两个并发请求同时提交同一个 Token，只有第一个能删除成功（返回 true），
 * 第二个删除时 Key 已不存在（返回 false）被拒绝。这就是“先消费、后业务”。</p>
 *
 * <p><b>Key 为什么是“用户 ID + 场景 + Token”？</b><br>
 * ① 带 userId：A 用户领取的 Token 拼出的 Key 属于 A，B 用户用同一个 Token
 * 拼出的 Key 不同，天然无法消费 A 的 Token（防串用）；<br>
 * ② 带 scene：同一用户创建活动和预约活动各有独立 Token，互不干扰。</p>
 *
 * <p><b>为什么 Redis 故障时必须拒绝写操作，而不是降级放行？</b><br>
 * 缓存、排行榜故障可以降级，因为它们只影响体验；幂等 Token 是防重复提交的
 * 保护 —— Redis 不可用时若继续放行，预约等写接口可能重复执行（如连续点击
 * 下单按钮产生多笔订单）。所以这里抛系统异常，让前端稍后重试更安全。</p>
 *
 * @author ming
 */
@Slf4j
@Service
public class IdempotencyTokenService {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 构造器注入 Redis 字符串操作模板。
     *
     * @param stringRedisTemplate Redis 字符串操作模板
     */
    public IdempotencyTokenService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 领取幂等 Token：写入 Redis 并设置 5 分钟 TTL，返回 Token 给前端。
     *
     * <p>Token 使用 {@link UUID#randomUUID()} 生成：128 位随机数，
     * 碰撞概率极低，不可能被前端猜出其他用户的 Token。</p>
     *
     * <p><b>为什么 Token 消费后要等业务完成才重新领取？</b><br>
     * Token 是“一次性”的：一旦业务开始（consume 成功），Token 即作废；
     * 即使业务本身失败（如名额已满），前端也必须重新领取 Token 再提交，
     * 不能复用旧 Token —— 这正是“防止重复提交”的语义。</p>
     *
     * @param scene 幂等场景（创建活动/预约/候补，必须是枚举中定义的值）
     * @return 一次性 Token 字符串
     * @throws BusinessException 未登录 / Redis 不可用
     */
    public String issueToken(IdempotencyScene scene) {
        // 0. 防御性校验：scene 必须非空（正常由 Controller 的 fromValue 保证，
        //    这里防止未来有人绕过 Controller 直接调用 Service 时出现空指针）
        if (scene == null) {
            throw new BusinessException(ResultCode.IDEMPOTENCY_SCENE_INVALID);
        }

        // 1. 获取当前登录用户：Token 属于具体用户，未登录无法领取
        Long userId = requireCurrentUserId();

        // 2. 生成高随机 Token（UUID 128 位随机数）
        String token = UUID.randomUUID().toString();

        // 3. 拼 Key：用户 ID + 场景 + Token，确保 A 用户的 Token 不能被 B 用户使用
        String key = buildTokenKey(userId, scene, token);

        // 4. 写入 Redis 并设置 5 分钟 TTL（过期后 Token 自动失效）
        try {
            stringRedisTemplate.opsForValue()
                    .set(key, "1", Duration.ofMinutes(RedisConstants.IDEMPOTENCY_TOKEN_TTL));
        } catch (Exception e) {
            // 幂等保护不可用必须拒绝放行：不能降级，否则写接口可能重复执行
            log.error("幂等 Token 写入失败（拒绝发放）：userId={}, scene={}", userId, scene.getValue(), e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "幂等服务暂不可用，请稍后重试");
        }
        log.debug("幂等 Token 发放成功：userId={}, scene={}", userId, scene.getValue());
        return token;
    }

    /**
     * 原子消费幂等 Token：业务开始前调用。
     *
     * <p>执行 Redis DELETE（原子操作）：</p>
     * <ul>
     *   <li><b>删除成功（true）</b>：Token 首次被使用，允许继续执行业务；</li>
     *   <li><b>删除失败（false）</b>：Token 无效、已过期或已被使用，拒绝重复提交。</li>
     * </ul>
     *
     * <p><b>为什么消费必须发生在业务开始前？</b><br>
     * 如果先执行业务再删 Token：两个并发请求可能都通过校验、都执行了业务，
     * 删除 Token 只是“事后清理”，起不到拦截作用。只有“先删除、后业务”，
     * 才能保证两个并发请求只有一个能删除成功、另一个被拒绝。</p>
     *
     * @param scene 幂等场景
     * @param token 前端通过请求头携带的 Token
     * @return true=首次提交（允许执行业务）；false=Token 无效/过期/已使用
     * @throws BusinessException 未登录 / Redis 不可用
     */
    public boolean consumeToken(IdempotencyScene scene, String token) {
        // 0. 防御性校验：scene 必须非空（与 issueToken 同理，防绕过 Controller 直接调用）
        if (scene == null) {
            throw new BusinessException(ResultCode.IDEMPOTENCY_SCENE_INVALID);
        }

        // 1. 获取当前登录用户：消费时必须用“当前用户”拼 Key，
        //    即使有人盗用了别人的 Token，拼出的 Key 也对不上，删除必然失败
        Long userId = requireCurrentUserId();

        // 2. Token 本身为空/空白：直接视为无效（无需访问 Redis）
        if (token == null || token.isBlank()) {
            return false;
        }

        // 3. 拼 Key 并原子删除
        String key = buildTokenKey(userId, scene, token);
        try {
            Boolean deleted = stringRedisTemplate.delete(key);
            return Boolean.TRUE.equals(deleted);
        } catch (Exception e) {
            // 同 issueToken：幂等保护不可用必须拒绝放行
            log.error("幂等 Token 消费失败（拒绝放行）：userId={}, scene={}", userId, scene.getValue(), e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "幂等服务暂不可用，请稍后重试");
        }
    }

    /**
     * 获取当前登录用户 ID，未登录直接抛异常。
     *
     * @return 当前登录用户 ID
     * @throws BusinessException 未登录
     */
    private Long requireCurrentUserId() {
        UserVO currentUser = UserHolder.getUserVO();
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return currentUser.getId();
    }

    /**
     * 拼接幂等 Token 的 Redis Key。
     *
     * <p>格式：idempotency:token:{userId}:{scene}:{token}，
     * 三个要素缺一不可：userId 防串用、scene 分场景、token 唯一标识一次提交。</p>
     *
     * @param userId 当前登录用户 ID
     * @param scene  幂等场景
     * @param token  一次性 Token
     * @return 完整 Redis Key
     */
    private String buildTokenKey(Long userId, IdempotencyScene scene, String token) {
        return RedisConstants.IDEMPOTENCY_TOKEN_KEY + userId + ":"
                + scene.getValue() + ":" + token;
    }
}
