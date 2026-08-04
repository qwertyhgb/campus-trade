package com.ming.campustrade.event;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 候补补位成功事件 —— 候补用户被自动补位为正式预约后触发。
 *
 * <p><b>【发送给谁】</b>补位成功的用户本人（userId）。<br>
 * <b>对应路由键：</b>{@code waitlist.promoted}<br>
 * <b>通知类型：</b>4（候补补位成功）</p>
 *
 * <p><b>【为什么这是候补模块最有价值的一条通知？】</b><br>
 * 用户加入候补后，最期待的就是"什么时候轮到我"。补位成功意味着
 * 前面有人取消了预约，名额空出来了，系统自动把该用户从候补转为正式预约。
 * 这条通知告知用户"恭喜，您已获得正式名额，可以参加活动了！"</p>
 *
 * <p><b>【补位流程回顾】</b><br>
 * 1. 用户 A 取消预约 → 释放 1 个名额<br>
 * 2. {@code WaitlistServiceImpl.promoteNext()} 被调用<br>
 * 3. 查询候补队列队首（{@code selectFirstWaiting}）<br>
 * 4. 把该候补记录标记为 PROMOTED，activeMark 置 NULL<br>
 * 5. 在 reservation 表插入一条正式预约记录<br>
 * 6. 发送 WaitlistPromotedEvent 通知用户<br>
 * 整个过程在 {@code promoteNext()} 的一笔独立事务中完成，补位失败不影响取消预约。</p>
 *
 * @author ming
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WaitlistPromotedEvent extends BaseNotificationEvent {

    /** 事件类型（大写蛇形，用于 eventType 字段，与路由键 waitlist.promoted 对应）。 */
    public static final String EVENT_TYPE = "WAITLIST_PROMOTED";

    /** 补位成功的活动 ID。 */
    private Long activityId;

    /** 候补记录 ID。 */
    private Long waitlistId;

    /**
     * 创建一条"候补补位成功"事件。
     *
     * @param userId     接收通知的用户 ID（补位成功的用户）
     * @param activityId 补位成功的活动 ID
     * @param waitlistId 候补记录 ID
     * @return 初始化完成的候补补位事件
     */
    public static WaitlistPromotedEvent create(Long userId, Long activityId, Long waitlistId) {
        WaitlistPromotedEvent event = new WaitlistPromotedEvent();
        event.initEvent(EVENT_TYPE, userId);
        event.setActivityId(activityId);
        event.setWaitlistId(waitlistId);
        return event;
    }
}