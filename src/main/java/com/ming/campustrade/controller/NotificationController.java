package com.ming.campustrade.controller;

import com.ming.campustrade.common.Result;
import com.ming.campustrade.service.NotificationService;
import com.ming.campustrade.vo.NotificationVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;

import com.baomidou.mybatisplus.core.metadata.IPage;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知管理控制器 —— 用户查询通知、已读管理。
 *
 * <p>通知的写入由 RabbitMQ 消费者异步完成，本控制器只负责读取和状态变更。</p>
 *
 * @author ming
 */
@Slf4j
@RestController
@RequestMapping("/notification")
@Validated
@Tag(name = "通知管理", description = "站内通知查询、已读管理")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 分页查询我的通知列表。
     *
     * @param pageNo     页码，从 1 开始
     * @param pageSize   每页条数，最大 50
     * @param unreadOnly 是否只看未读（默认 false=全部）
     * @return 通知分页结果
     */
    @Operation(summary = "我的通知", description = "分页查询当前登录用户的通知列表，支持按未读过滤")
    @GetMapping("/my")
    public Result<IPage<NotificationVO>> getMyNotifications(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") @Min(1) @Max(50) int pageSize,
            @Parameter(description = "是否只看未读") @RequestParam(defaultValue = "false") boolean unreadOnly) {
        log.info("查询我的通知：pageNo={}, pageSize={}, unreadOnly={}", pageNo, pageSize, unreadOnly);
        IPage<NotificationVO> page = notificationService.getMyNotifications(pageNo, pageSize, unreadOnly);
        return Result.success(page);
    }

    /**
     * 查询当前用户未读通知数量。
     */
    @Operation(summary = "未读数量", description = "查询当前登录用户的未读通知数量")
    @GetMapping("/unread-count")
    public Result<Long> getUnreadCount() {
        long count = notificationService.getUnreadCount();
        return Result.success(count);
    }

    /**
     * 标记单条通知为已读。
     *
     * @param id 通知 ID
     */
    @Operation(summary = "标记已读", description = "标记单条通知为已读（只能标记自己的通知）")
    @PutMapping("/{id}/read")
    public Result<Void> markAsRead(
            @Parameter(description = "通知 ID") @PathVariable Long id) {
        log.info("标记通知已读：notificationId={}", id);
        notificationService.markAsRead(id);
        return Result.success();
    }

    /**
     * 标记当前用户所有通知为已读。
     */
    @Operation(summary = "全部已读", description = "标记当前登录用户的所有通知为已读")
    @PutMapping("/read-all")
    public Result<Void> markAllAsRead() {
        log.info("标记全部通知已读");
        notificationService.markAllAsRead();
        return Result.success();
    }
}