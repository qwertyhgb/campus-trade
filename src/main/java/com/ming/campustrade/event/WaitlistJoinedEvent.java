package com.ming.campustrade.event;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 加入候补事件 —— 用户加入候补队列后触发。
 *
 * <p><b>【发送给谁】</b>加入候补的用户本人（userId）。<br>
 * <b>对应路由键：</b>{@code waitlist.joined}<br>
 * <b>通知类型：</b>3（加入候补）</p>
 *
 * <p><b>【为什么要发这条通知？】</b><br>
 * 用户加入候补后，最关心的是"我排在第几位？还要等多久？"
 * 这条通知告知用户已成功进入候补队列，并附上当前排队位置，
 * 让用户心里有数。后续如果补位成功，还有 WaitlistPromotedEvent 通知。</p>
 *
 * <p><b>【事件发送时机】</b><br>
 * 在 {@code WaitlistServiceImpl.joinWaitlist()} 方法中，候补记录插入成功后发送。
 * 发送时把 {@code queuePosition} 也传过去，消费者组装通知内容时可以直接展示
 * "您已加入候补队列，当前排在第 X 位"。</p>
 *
 * @author ming
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WaitlistJoinedEvent extends BaseNotificationEvent {

    /** 事件类型（大写蛇形，用于 eventType 字段，与路由键 waitlist.joined 对应）。 */
    public static final String EVENT_TYPE = "WAITLIST_JOINED";

    /** 加入候补的活动 ID。 */
    private Long activityId;

    /** 候补记录 ID。 */
    private Long waitlistId;

    /**
     * 加入时计算出的排队位置快照，从 1 开始。
     *
     * <p>它只用于生成“您已排在第几位”的即时通知，
     * 用户后来查询实时位置时仍然要以数据库动态计算结果为准。</p>
     */
    private Integer queuePosition;

    /**
     * 创建一条"加入候补"事件。
     *
     * @param userId     接收通知的用户 ID（加入候补的用户）
     * @param activityId 加入候补的活动 ID
     * @param waitlistId   候补记录 ID
     * @param queuePosition 加入时的排队位置快照
     * @return 初始化完成的加入候补事件
     */
    public static WaitlistJoinedEvent create(Long userId, Long activityId,
                                             Long waitlistId, Integer queuePosition) {
        WaitlistJoinedEvent event = new WaitlistJoinedEvent();
        event.initEvent(EVENT_TYPE, userId);
        event.setActivityId(activityId);
        event.setWaitlistId(waitlistId);
        event.setQueuePosition(queuePosition);
        return event;
    }
}
