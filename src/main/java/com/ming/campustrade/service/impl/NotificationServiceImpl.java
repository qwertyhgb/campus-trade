package com.ming.campustrade.service.impl;

import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.entity.MessageConsumeRecord;
import com.ming.campustrade.entity.Notification;
import com.ming.campustrade.event.ActivityReviewedEvent;
import com.ming.campustrade.event.ActivityUpcomingEvent;
import com.ming.campustrade.event.BaseNotificationEvent;
import com.ming.campustrade.event.ReservationCanceledEvent;
import com.ming.campustrade.event.ReservationCreatedEvent;
import com.ming.campustrade.event.WaitlistJoinedEvent;
import com.ming.campustrade.event.WaitlistPromotedEvent;
import com.ming.campustrade.mapper.MessageConsumeRecordMapper;
import com.ming.campustrade.mapper.NotificationMapper;
import com.ming.campustrade.service.ExternalNotificationService;
import com.ming.campustrade.service.NotificationService;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.NotificationVO;
import com.ming.campustrade.vo.UserVO;

import lombok.extern.slf4j.Slf4j;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 站内通知服务实现类 —— 消费事件、写入通知、保证幂等 + 用户查询与已读管理。
 *
 * <p><b>【幂等消费】</b>依赖 uk_event_id 唯一索引，INSERT 冲突 → 重复消息跳过。<br>
 * <b>【用户查询】</b>所有查询/更新都带 user_id 条件，防止越权看到别人的通知。</p>
 *
 * @author ming
 */
@Slf4j
@Service
@SuppressWarnings("null")
public class NotificationServiceImpl implements NotificationService {

    private final MessageConsumeRecordMapper consumeRecordMapper;
    private final NotificationMapper notificationMapper;
    private final ExternalNotificationService externalNotificationService;

    public NotificationServiceImpl(MessageConsumeRecordMapper consumeRecordMapper,
                                   NotificationMapper notificationMapper,
                                   ExternalNotificationService externalNotificationService) {
        this.consumeRecordMapper = consumeRecordMapper;
        this.notificationMapper = notificationMapper;
        this.externalNotificationService = externalNotificationService;
    }

    // ==================== 幂等消费（消费者写入） ====================

    @Override
    @Transactional
    public boolean processIfNew(BaseNotificationEvent event, String queueName) {
        MessageConsumeRecord record = new MessageConsumeRecord();
        record.setEventId(event.getEventId());
        record.setQueueName(queueName);
        record.setConsumeStatus(1);
        try {
            consumeRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.info("重复消息已跳过（消费幂等）：eventId={}, eventType={}",
                    event.getEventId(), event.getEventType());
            return false;
        }
        Notification notification = buildNotification(event);
        notification.setUserId(event.getUserId());
        notificationMapper.insert(notification);

        // 站内通知成功落库后，再模拟分发到邮件或短信渠道。
        // 模拟渠道失败不影响站内通知：站内消息是本项目的核心结果，
        // 外部渠道只是附加能力，不能因为第三方平台暂时不可用而让消费事务回滚。
        sendSimulatedExternalNotification(event, notification);

        log.info("通知已生成：eventId={}, eventType={}, userId={}, type={}",
                event.getEventId(), event.getEventType(), event.getUserId(), notification.getType());
        return true;
    }

    /**
     * 根据事件类型选择一个模拟外部渠道。
     *
     * <p>审核结果适合模拟邮件，候补补位和活动提醒适合模拟短信；
     * 其他事件暂时只生成站内通知，避免每种事件同时触发多个外部渠道。</p>
     */
    private void sendSimulatedExternalNotification(BaseNotificationEvent event,
                                                    Notification notification) {
        try {
            if (event instanceof ActivityReviewedEvent) {
                externalNotificationService.sendSimulatedEmail(
                        notification.getUserId(), notification.getTitle(), notification.getContent());
            } else if (event instanceof WaitlistPromotedEvent
                    || event instanceof ActivityUpcomingEvent) {
                externalNotificationService.sendSimulatedSms(
                        notification.getUserId(), notification.getContent());
            }
        } catch (Exception e) {
            // 这里只是模拟外部渠道；真实项目通常会把外部发送单独建表、重试，
            // 而不是让外部服务故障回滚已经成功生成的站内通知。
            log.error("模拟外部通知失败，但站内通知保持成功：eventId={}, userId={}",
                    event.getEventId(), event.getUserId(), e);
        }
    }

    // ==================== 用户查询 ====================

    /**
     * 分页查询当前用户的通知列表。
     *
     * <p><b>安全约束：</b>查询条件必须带 user_id = 当前用户 ID ——
     * 如果只根据通知 ID 查询，用户可以遍历 ID 看到别人的通知内容。</p>
     */
    @Override
    public IPage<NotificationVO> getMyNotifications(int pageNo, int pageSize, boolean unreadOnly) {
        // 1. 获取当前登录用户
        UserVO currentUser = UserHolder.getUserVO();
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long userId = currentUser.getId();

        // 2. 构建分页查询
        // 等价 SQL:
        //   SELECT COUNT(*) FROM notification WHERE user_id = ?  [AND is_read = 0]
        //   SELECT * FROM notification WHERE user_id = ?  [AND is_read = 0]
        //   ORDER BY create_time DESC LIMIT ?, ?
        Page<Notification> pageParam = new Page<>(pageNo, pageSize);
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getUserId, userId);
        if (unreadOnly) {
            wrapper.eq(Notification::getIsRead, 0);
        }
        wrapper.orderByDesc(Notification::getCreateTime);
        IPage<Notification> notificationPage = notificationMapper.selectPage(pageParam, wrapper);

        // 3. 转换为 VO（不返回 userId，只暴露前端需要的内容）
        IPage<NotificationVO> resultPage = new Page<>(notificationPage.getCurrent(),
                notificationPage.getSize(), notificationPage.getTotal());
        resultPage.setRecords(notificationPage.getRecords().stream().map(n -> {
            NotificationVO vo = new NotificationVO();
            vo.setId(n.getId());
            vo.setType(n.getType());
            vo.setTitle(n.getTitle());
            vo.setContent(n.getContent());
            vo.setRelatedId(n.getRelatedId());
            vo.setIsRead(n.getIsRead());
            vo.setCreateTime(n.getCreateTime());
            return vo;
        }).toList());
        return resultPage;
    }

    /**
     * 查询当前用户未读通知数量。
     */
    @Override
    public long getUnreadCount() {
        UserVO currentUser = UserHolder.getUserVO();
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getUserId, currentUser.getId())
                .eq(Notification::getIsRead, 0);
        return notificationMapper.selectCount(wrapper);
    }

    // ==================== 已读管理 ====================

    /**
     * 标记单条通知为已读。
     *
     * <p>使用条件更新防止越权：WHERE id = ? AND user_id = ?。
     * 这里故意不把 {@code is_read = 0} 放进条件：重复点击“标记已读”应该是幂等成功，
     * 而不是把正常的重复操作误报成“通知不存在”。</p>
     */
    @Override
    public void markAsRead(Long notificationId) {
        UserVO currentUser = UserHolder.getUserVO();
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (notificationId == null || notificationId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "通知ID不合法");
        }

        LambdaUpdateWrapper<Notification> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Notification::getId, notificationId)
                .eq(Notification::getUserId, currentUser.getId())
                .set(Notification::getIsRead, 1);
        int rows = notificationMapper.update(null, wrapper);
        if (rows == 0) {
            // 只可能是通知不存在，或通知属于其他用户；统一返回不存在，避免泄露信息。
            throw new BusinessException(ResultCode.NOTIFICATION_NOT_FOUND);
        }
        log.info("通知已标记为已读：userId={}, notificationId={}",
                currentUser.getId(), notificationId);
    }

    /**
     * 标记当前用户所有通知为已读。
     */
    @Override
    public void markAllAsRead() {
        UserVO currentUser = UserHolder.getUserVO();
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        LambdaUpdateWrapper<Notification> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Notification::getUserId, currentUser.getId())
                .eq(Notification::getIsRead, 0)
                .set(Notification::getIsRead, 1);
        int rows = notificationMapper.update(null, wrapper);
        log.info("全部标记已读：userId={}, 影响 {} 条", currentUser.getId(), rows);
    }

    // ==================== 通知内容构建（消费者写入时调用） ====================

    /**
     * 把事件对象转换成 Notification（不含 userId，由调用方补充）。
     */
    private Notification buildNotification(BaseNotificationEvent event) {
        Notification notification = new Notification();
        notification.setIsRead(0);
        if (event instanceof ReservationCreatedEvent e) {
            notification.setType(1);
            notification.setTitle("预约成功");
            notification.setContent("您已成功预约活动（活动ID：" + e.getActivityId()
                    + "，预约ID：" + e.getReservationId() + "）");
            notification.setRelatedId(e.getActivityId());
        } else if (event instanceof ReservationCanceledEvent e) {
            notification.setType(2);
            notification.setTitle("预约取消");
            notification.setContent("有用户取消了活动（活动ID：" + e.getActivityId()
                    + "）的预约，预约ID：" + e.getReservationId());
            notification.setRelatedId(e.getActivityId());
        } else if (event instanceof WaitlistJoinedEvent e) {
            notification.setType(3);
            notification.setTitle("加入候补");
            notification.setContent("您已进入活动（活动ID：" + e.getActivityId()
                    + "）的候补队列，当前排在第 " + e.getQueuePosition()
                    + " 位，候补ID：" + e.getWaitlistId());
            notification.setRelatedId(e.getActivityId());
        } else if (event instanceof WaitlistPromotedEvent e) {
            notification.setType(4);
            notification.setTitle("候补补位成功");
            notification.setContent("恭喜！您在活动（活动ID：" + e.getActivityId()
                    + "）的候补已转为正式预约，候补ID：" + e.getWaitlistId());
            notification.setRelatedId(e.getActivityId());
        } else if (event instanceof ActivityReviewedEvent e) {
            notification.setRelatedId(e.getActivityId());
            if (Boolean.TRUE.equals(e.getPassed())) {
                notification.setType(5);
                notification.setTitle("审核通过");
                notification.setContent("您的活动（活动ID：" + e.getActivityId() + "）已通过审核并上架");
            } else {
                notification.setType(6);
                notification.setTitle("审核未通过");
                notification.setContent("您的活动（活动ID：" + e.getActivityId()
                        + "）审核未通过，原因：" + e.getRejectReason());
            }
        } else if (event instanceof ActivityUpcomingEvent e) {
            // 活动即将开始 → 通知已预约的用户
            notification.setType(7);
            notification.setTitle("活动即将开始");
            notification.setContent("您预约的活动「" + e.getActivityTitle()
                    + "」（活动ID：" + e.getActivityId()
                    + "）即将开始，开始时间：" + e.getStartTime());
            notification.setRelatedId(e.getActivityId());
        } else {
            log.warn("未知事件类型，无法生成通知：eventId={}, eventType={}",
                    event.getEventId(), event.getEventType());
            throw new IllegalArgumentException("未知事件类型：" + event.getEventType());
        }
        return notification;
    }
}
