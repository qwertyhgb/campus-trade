package com.ming.campustrade.controller;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ming.campustrade.common.Result;
import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.constant.IdempotencyScene;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.service.IdempotencyTokenService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 幂等 Token 控制器：为前端提供领取一次性 Token 的接口。
 *
 * <p><b>使用流程：</b></p>
 * <ol>
 *   <li>前端在提交写请求（创建活动/预约/加入候补）<b>之前</b>，先调用
 *       POST /idempotency/token/{scene} 领取一个一次性 Token；</li>
 *   <li>提交业务请求时，把 Token 放入 <b>Idempotency-Token</b> 请求头；</li>
 *   <li>后端在处理业务前原子消费（删除）该 Token：删除成功才执行业务，
 *       删除失败说明 Token 已用过/已过期，拒绝本次提交。</li>
 * </ol>
 *
 * <p>这样即使用户连续快速点击提交按钮，也只有携带同一 Token 的第一次请求
 * 能通过，后续重复请求都会被拦截 —— 防止创建活动、预约、候补等写操作重复执行。</p>
 *
 * <p><b>安全说明：</b>Token 必须在登录后领取（Security 的 anyRequest().authenticated()
 * 已兜底），Service 内部还会再次校验当前登录用户。</p>
 *
 * @author ming
 */
@Slf4j
@Tag(name = "幂等 Token", description = "为写操作领取一次性防重复提交 Token")
@RestController
@RequestMapping("/idempotency")
@Validated
public class IdempotencyTokenController {

    /** 幂等 Token 服务：负责 Token 的发放与消费。 */
    @Resource
    private IdempotencyTokenService idempotencyTokenService;

    /**
     * 领取幂等 Token（需登录）。
     *
     * <p>前端把返回的 Token 放入 <b>Idempotency-Token</b> 请求头，
     * 随业务请求一起提交；业务接口会在开始处理前消费（删除）该 Token。</p>
     *
     * @param scene 业务场景，只允许三个固定值：
     *              activity:create（创建活动）/ activity:reserve（预约）/
     *              activity:waitlist（加入候补）
     * @return 一次性 Token 字符串（5 分钟内有效）
     */
    @Operation(summary = "领取幂等 Token",
            description = "提交写请求前先领取一次性 Token，放入 Idempotency-Token 请求头；"
                    + "场景仅支持 activity:create / activity:reserve / activity:waitlist")
    @PostMapping("/token/{scene}")
    public Result<String> issueToken(
            @Parameter(description = "业务场景：activity:create / activity:reserve / activity:waitlist")
            @PathVariable String scene) {
        // 场景字符串必须落在枚举的三个固定值上，否则直接拒绝：
        // 防止前端随意传任意场景字符串拼出不可控的 Redis Key
        IdempotencyScene idempotencyScene = IdempotencyScene.fromValue(scene);
        if (idempotencyScene == null) {
            throw new BusinessException(ResultCode.IDEMPOTENCY_SCENE_INVALID);
        }
        log.info("领取幂等 Token：scene={}", scene);
        String token = idempotencyTokenService.issueToken(idempotencyScene);
        log.info("领取幂等 Token 成功：scene={}", scene);
        return Result.success(token);
    }
}
