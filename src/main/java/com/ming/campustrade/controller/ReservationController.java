package com.ming.campustrade.controller;

import java.util.List;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ming.campustrade.common.Result;
import com.ming.campustrade.service.ReservationService;
import com.ming.campustrade.vo.ReservationVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.extern.slf4j.Slf4j;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预约管理控制器 —— 处理活动的预约、取消预约、查询预约等 HTTP 请求。
 *
 * <h2>权限约定</h2>
 * <ul>
 *   <li>预约/取消/我的预约：登录即可（SecurityConfig 的 anyRequest().authenticated() 兜底）</li>
 *   <li>组织者预约名单：@PreAuthorize 管角色（ORGANIZER/ADMIN）+ Service 层管归属（只能看自己的活动）</li>
 * </ul>
 *
 * @author ming
 */
@Slf4j
@Tag(name = "预约管理", description = "活动预约、取消预约、我的预约、组织者查看预约名单")
@RestController
@RequestMapping("/reservation")
@Validated // 启用方法参数（@RequestParam/@PathVariable）上的约束校验（如 @Min/@Max）
public class ReservationController {

    private final ReservationService reservationService;

    /**
     * 构造器注入：Spring 启动时自动把 ReservationService 的实现类实例传进来。
     */
    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /**
     * 预约活动（需登录）。
     *
     * <p>核心并发控制：防重复、防超额、防非法时间、防自约，
     * 具体逻辑在 ReservationService.reserve() 中实现。</p>
     *
     * @param activityId 活动 ID（路径变量）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "预约活动", description = "用户预约活动（需登录），含防重复/防超额/防非法时间/防自约校验")
    @PostMapping("/{activityId}")
    public Result<Void> reserve(@Parameter(description = "活动ID") @PathVariable Long activityId) {
        log.info("预约活动：activityId={}", activityId);
        reservationService.reserve(activityId);
        return Result.success();
    }

    /**
     * 取消预约（需登录）。
     *
     * <p>用户取消自己的预约：释放活动名额，预约记录标记为已取消（保留历史）。</p>
     *
     * @param activityId 活动 ID（路径变量）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "取消预约", description = "用户取消自己的预约（需登录），释放活动名额")
    @DeleteMapping("/{activityId}")
    public Result<Void> cancel(@Parameter(description = "活动ID") @PathVariable Long activityId) {
        log.info("取消预约：activityId={}", activityId);
        reservationService.cancelReservation(activityId);
        return Result.success();
    }

    /**
     * 我的预约列表（需登录）。
     *
     * <p>返回当前用户全部预约记录（含已取消的历史），按预约时间倒序。</p>
     *
     * @return 预约 VO 列表
     */
    @Operation(summary = "我的预约", description = "查询当前用户的全部预约记录（含历史，按预约时间倒序）")
    @GetMapping("/my")
    public Result<List<ReservationVO>> my() {
        log.info("查询我的预约列表");
        List<ReservationVO> list = reservationService.getMyReservations();
        return Result.success(list);
    }

    /**
     * 组织者查看预约名单（仅 ORGANIZER/ADMIN 角色，且必须是活动组织者本人）。
     *
     * <p>权限双重校验：<br>
     * 1. @PreAuthorize 管角色：只有 ORGANIZER/ADMIN 能进这个接口（普通用户 403）<br>
     * 2. Service 层管归属：必须是自己组织的活动（别的组织者看不了你的名单）</p>
     *
     * <p>分页参数限制：page 最小 1，size 限制 1~50（类上 @Validated 让 @Min/@Max 生效）。</p>
     *
     * @param activityId 活动 ID（路径变量）
     * @param page       页码，默认 1
     * @param size       每页条数，默认 10，最大 50
     * @return 预约 VO 分页对象（含预约用户信息）
     */
    @Operation(summary = "预约名单", description = "组织者分页查看某活动的预约名单（仅活动组织者或管理员）")
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    @GetMapping("/activity/{activityId}")
    public Result<IPage<ReservationVO>> list(
            @Parameter(description = "活动ID") @PathVariable Long activityId,
            @Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @Parameter(description = "每页条数，最大50") @RequestParam(defaultValue = "10")
            @Min(1) @Max(50) Integer size) {
        log.info("查询预约名单：activityId={}, page={}, size={}", activityId, page, size);
        IPage<ReservationVO> result = reservationService.getActivityReservations(activityId, page, size);
        return Result.success(result);
    }
}
