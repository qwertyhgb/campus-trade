package com.ming.campustrade.controller;

import java.util.List;

import com.ming.campustrade.common.Result;
import com.ming.campustrade.service.WaitlistService;
import com.ming.campustrade.vo.WaitlistVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.constraints.Min;

import lombok.extern.slf4j.Slf4j;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 候补管理控制器 —— 处理活动候补的加入、取消、查询等 HTTP 请求。
 *
 * <h2>权限约定</h2>
 * <ul>
 *   <li>候补是普通用户的主要入口（活动满员时想参加的途径），因此全部接口"登录即可"</li>
 *   <li>无需 @PreAuthorize：SecurityConfig 的 anyRequest().authenticated() 已兜底</li>
 *   <li>业务校验（是否满员、是否重复候补、排队位置计算）统一在 WaitlistService 中完成</li>
 * </ul>
 *
 * @author ming
 */
@Slf4j
@Tag(name = "候补管理", description = "活动候补的加入、取消、我的候补、排队位置")
@RestController
@RequestMapping("/waitlist")
@Validated // 启用方法参数（@PathVariable）上的约束校验（如 @Min）
public class WaitlistController {

    private final WaitlistService waitlistService;

    /**
     * 构造器注入：Spring 启动时自动把 WaitlistService 的实现类实例传进来。
     */
    public WaitlistController(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    /**
     * 加入候补队列（需登录）。
     *
     * <p>活动名额已满时调用。并发控制由 Service 层的悲观锁（FOR UPDATE）
     * + 唯一索引兜底完成，保证排队位置不重复、顺序公平。</p>
     *
     * @param activityId 活动 ID（路径变量）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "加入候补", description = "活动满员时用户加入候补队列（需登录），含满员/防重复/防自约校验")
    @PostMapping("/{activityId}")
    public Result<Void> join(
            @Parameter(description = "活动ID")
            @PathVariable @Min(value = 1, message = "活动ID必须大于0") Long activityId) {
        log.info("加入候补：activityId={}", activityId);
        waitlistService.joinWaitlist(activityId);
        return Result.success();
    }

    /**
     * 取消候补（需登录）。
     *
     * <p>用户主动退出候补队列，释放排队位置；取消后可以重新加入。</p>
     *
     * @param activityId 活动 ID（路径变量）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "取消候补", description = "用户取消自己的候补（需登录），释放排队位置")
    @DeleteMapping("/{activityId}")
    public Result<Void> cancel(
            @Parameter(description = "活动ID")
            @PathVariable @Min(value = 1, message = "活动ID必须大于0") Long activityId) {
        log.info("取消候补：activityId={}", activityId);
        waitlistService.cancelWaitlist(activityId);
        return Result.success();
    }

    /**
     * 我的候补列表（需登录）。
     *
     * <p>返回当前用户全部候补记录（含候补中、已补位、已取消的历史），
     * 按创建时间倒序，前端根据 status 字段自行决定展示样式。</p>
     *
     * @return 候补 VO 列表（无记录时返回空列表）
     */
    @Operation(summary = "我的候补", description = "查询当前用户的全部候补记录（含历史，按创建时间倒序）")
    @GetMapping("/my")
    public Result<List<WaitlistVO>> my() {
        log.info("查询我的候补列表");
        List<WaitlistVO> list = waitlistService.getMyWaitlists();
        return Result.success(list);
    }

    /**
     * 我的实际排队位置（需登录）。
     *
     * <p>动态计算当前用户在指定活动候补队列中的实际位置（从 1 开始）：
     * 排在前面的人取消候补后，实际位置会自动提前。</p>
     *
     * @param activityId 活动 ID（路径变量）
     * @return 实际排队位置数字
     */
    @Operation(summary = "我的排队位置", description = "动态查询当前用户在指定活动的实际候补排队位置（从1开始）")
    @GetMapping("/{activityId}/position")
    public Result<Integer> position(
            @Parameter(description = "活动ID")
            @PathVariable @Min(value = 1, message = "活动ID必须大于0") Long activityId) {
        log.info("查询我的排队位置：activityId={}", activityId);
        Integer position = waitlistService.getMyWaitlistPosition(activityId);
        return Result.success(position);
    }
}
