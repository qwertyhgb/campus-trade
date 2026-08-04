package com.ming.campustrade.event;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 取消预约事件 —— 用户取消预约后触发。
 *
 * <p><b>【发送给谁】</b>活动组织者（userId = 组织者 ID），而不是取消者本人。<br>
 * <b>对应路由键：</b>{@code reservation.canceled}<br>
 * <b>通知类型：</b>2（预约取消）</p>
 *
 * <p><b>【为什么发给组织者而不是取消者？】</b><br>
 * 取消者自己发起的取消操作，不需要通知自己。但组织者需要知道
 * 有人取消预约了，因为取消会释放名额，触发候补补位流程。
 * 组织者收到通知后可以关注：名额是否被候补用户补上了？</p>
 *
 * <p><b>【事件发送时机】</b><br>
 * 在 {@code ReservationServiceImpl.cancelReservation()} 方法中，
 * 取消预约成功 + 名额释放后发送。注意：先释放名额、再发事件，
 * 即使事件发送失败，业务数据（取消 + 名额释放）已经提交，
 * 不会影响用户取消预约的核心功能。事件是"附加通知"，不是"核心业务"。</p>
 *
 * @author ming
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ReservationCanceledEvent extends BaseNotificationEvent {

    /** 事件类型（大写蛇形，用于 eventType 字段，与路由键 reservation.canceled 对应）。 */
    public static final String EVENT_TYPE = "RESERVATION_CANCELED";

    /** 被取消预约的活动 ID。 */
    private Long activityId;

    /** 被取消的预约记录 ID。 */
    private Long reservationId;

    /**
     * 创建一条"取消预约"事件。
     *
     * @param userId        接收通知的用户 ID（活动组织者）
     * @param activityId    被取消预约的活动 ID
     * @param reservationId 被取消的预约记录 ID
     * @return 初始化完成的取消预约事件
     */
    public static ReservationCanceledEvent create(Long userId, Long activityId, Long reservationId) {
        ReservationCanceledEvent event = new ReservationCanceledEvent();
        event.initEvent(EVENT_TYPE, userId);
        event.setActivityId(activityId);
        event.setReservationId(reservationId);
        return event;
    }
}