package com.ming.campustrade.config;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ming.campustrade.common.constant.ActivityStatus;
import com.ming.campustrade.entity.Activity;
import com.ming.campustrade.entity.Reservation;
import com.ming.campustrade.event.ActivityUpcomingEvent;
import com.ming.campustrade.mapper.ActivityMapper;
import com.ming.campustrade.mapper.ReservationMapper;
import com.ming.campustrade.messaging.NotificationEventPublisher;

import lombok.extern.slf4j.Slf4j;

/**
 * 活动即将开始通知定时任务 —— 每分钟扫描即将开始的活动，给已预约用户发送提醒。
 *
 * <p><b>【与 ActivityStatusTask 的分工】</b><br>
 * ActivityStatusTask 负责修改活动状态（报名结束 → 进行中 → 已结束），
 * 本任务只负责发送通知消息，两者职责不同，互不干扰。</p>
 *
 * <p><b>【通知策略】</b><br>
 * 活动开始前 30 分钟发送。使用固定 eventId（UPCOMING_活动ID_用户ID），
 * 配合 message_consume_record 的 uk_event_id 唯一索引，保证同一个用户
 * 对同一个活动只生成一条通知，重复扫描、重启都不会重复发送。</p>
 *
 * <p><b>【发送失败处理】</b><br>
 * 发送失败只记录错误日志，不额外处理。下次定时扫描时由于 eventId 固定，
 * 如果之前已成功消费过，唯一索引拦下重复消息；
 * 如果之前发送失败（未入库），下次扫描会重新发送。</p>
 *
 * @author ming
 */
@Slf4j
@Component
@SuppressWarnings("null")
public class ActivityUpcomingNotificationTask {

    /** 提前通知的时间（分钟）。 */
    private static final long ADVANCE_MINUTES = 30;

    private final ActivityMapper activityMapper;
    private final ReservationMapper reservationMapper;
    private final NotificationEventPublisher notificationEventPublisher;

    public ActivityUpcomingNotificationTask(ActivityMapper activityMapper,
                                            ReservationMapper reservationMapper,
                                            NotificationEventPublisher notificationEventPublisher) {
        this.activityMapper = activityMapper;
        this.reservationMapper = reservationMapper;
        this.notificationEventPublisher = notificationEventPublisher;
    }

    /**
     * 每分钟扫描一次，查找即将开始的活动并发送通知。
     *
     * <p>等价 SQL（查询活动）：</p>
     * <pre>
     * SELECT * FROM activity
     * WHERE status IN (3, 4)                    -- 报名中或报名结束但活动未开始
     *   AND start_time > NOW()                  -- 还没开始
     *   AND start_time &lt;= NOW() + 30 分钟        -- 30 分钟内开始
     *   AND deleted = 0                         -- 未逻辑删除
     * </pre>
     */
    @Scheduled(fixedRate = 60000)
    public void notifyUpcomingActivities() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.plusMinutes(ADVANCE_MINUTES);

        // 1. 查询即将开始的活动
        // 条件：活动尚未开始、开始时间在未来且在 30 分钟内。
        // 报名截止后活动状态会变为 ENROLL_ENDED，但活动仍可能距离开始还有 30 分钟，
        // 因此不能只查询 ENROLLING，否则会漏发提醒。
        LambdaQueryWrapper<Activity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(Activity::getStatus,
                        ActivityStatus.ENROLLING, ActivityStatus.ENROLL_ENDED)
                .gt(Activity::getStartTime, now)                    // 还没开始
                .le(Activity::getStartTime, threshold)               // 30 分钟内开始
                .orderByAsc(Activity::getStartTime);                 // 按开始时间升序，方便排查
        List<Activity> upcomingActivities = activityMapper.selectList(queryWrapper);

        if (upcomingActivities.isEmpty()) {
            // 没有即将开始的活动，本次扫描无事可做
            return;
        }

        log.info("活动即将开始通知：扫描到 {} 个活动即将开始", upcomingActivities.size());

        // 2. 一次批量查询所有活动的有效预约，避免每个活动单独查询造成 N+1 查询。
        List<Long> activityIds = upcomingActivities.stream()
                .map(Activity::getId)
                .filter(Objects::nonNull)
                .toList();
        List<Reservation> activeReservations = activityIds.isEmpty()
                ? List.of()
                : reservationMapper.selectActiveReservationsByActivityIds(activityIds);
        Map<Long, List<Reservation>> reservationsByActivity = activeReservations.stream()
                .filter(reservation -> reservation.getActivityId() != null)
                .collect(Collectors.groupingBy(Reservation::getActivityId));

        // 3. 对每个活动的有效预约用户发送通知
        for (Activity activity : upcomingActivities) {
            List<Reservation> reservations = reservationsByActivity
                    .getOrDefault(activity.getId(), List.of());

            if (reservations.isEmpty()) {
                log.debug("活动没有有效预约，跳过通知：activityId={}", activity.getId());
                continue;
            }

            // 对每个预约用户发送一条通知事件
            for (Reservation reservation : reservations) {
                // 使用固定 eventId 保证幂等：UPCOMING_{activityId}_{userId}
                ActivityUpcomingEvent event = ActivityUpcomingEvent.create(
                        reservation.getUserId(),
                        activity.getId(),
                        activity.getTitle(),
                        activity.getStartTime());

                try {
                    notificationEventPublisher.publishActivityUpcoming(event);
                } catch (Exception e) {
                    // 发送失败只记录日志，不影响其他用户的通知发送
                    // 下次扫描时由于 eventId 固定，已消费的会被幂等拦截，
                    // 未消费的会重新发送
                    log.error("活动即将开始通知发送失败：activityId={}, userId={}, eventId={}",
                            activity.getId(), reservation.getUserId(), event.getEventId(), e);
                }
            }
        }
    }
}
