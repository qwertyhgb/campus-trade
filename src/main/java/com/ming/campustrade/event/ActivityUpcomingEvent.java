package com.ming.campustrade.event;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 活动即将开始事件 —— 定时任务扫描到活动即将开始（默认提前 30 分钟）时触发。
 *
 * <p><b>【发送给谁】</b>已成功预约该活动的用户（userId）。<br>
 * <b>对应路由键：</b>{@code activity.upcoming}<br>
 * <b>通知类型：</b>7（活动即将开始）</p>
 *
 * <p><b>【eventId 的生成规则（幂等关键）】</b><br>
 * 与普通业务事件不同，本事件的 eventId 不能每次随机生成 UUID ——
 * 因为定时任务每分钟扫描一次，如果每次都生成新的 UUID，同一用户同一活动
 * 每分钟都会收到一条重复通知。<br>
 * 正确做法：eventId = 固定前缀 + 活动 ID + 用户 ID，例如<br>
 * {@code "UPCOMING_" + activityId + "_" + userId}<br>
 * 这样同一个用户对同一个活动永远只有一个固定 eventId。<br>
 * message_consume_record 表的 uk_event_id 唯一索引保证最终只写入一条通知。</p>
 *
 * @author ming
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ActivityUpcomingEvent extends BaseNotificationEvent {

    /** 事件类型（大写蛇形，用于 eventType 字段，与路由键 activity.upcoming 对应）。 */
    public static final String EVENT_TYPE = "ACTIVITY_UPCOMING";

    /** 即将开始的活动 ID。 */
    private Long activityId;

    /** 活动标题（用于生成更友好的通知内容）。 */
    private String activityTitle;

    /** 活动开始时间（用于在通知中展示具体时间）。 */
    private LocalDateTime startTime;

    /**
     * 创建一条"活动即将开始"事件。
     *
     * <p><b>eventId 生成规则：</b>{@code "UPCOMING_" + activityId + "_" + userId}，
     * 保证同一个用户对同一活动只有一条通知记录，配合消费幂等实现去重。</p>
     *
     * @param userId        接收通知的用户 ID（已预约该活动的用户）
     * @param activityId    活动 ID
     * @param activityTitle 活动标题
     * @param startTime     活动开始时间
     * @return 初始化完成的活动即将开始事件
     */
    public static ActivityUpcomingEvent create(Long userId, Long activityId,
                                                String activityTitle, LocalDateTime startTime) {
        ActivityUpcomingEvent event = new ActivityUpcomingEvent();
        // eventId 使用固定规则生成，不是随机 UUID，保证幂等
        event.setEventId("UPCOMING_" + activityId + "_" + userId);
        event.setEventType(EVENT_TYPE);
        event.setOccurredAt(LocalDateTime.now());
        event.setUserId(userId);
        event.setActivityId(activityId);
        event.setActivityTitle(activityTitle);
        event.setStartTime(startTime);
        return event;
    }
}
