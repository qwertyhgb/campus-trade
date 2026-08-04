package com.ming.campustrade.event;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 预约成功事件 —— 用户预约活动成功后触发。
 *
 * <p><b>【发送给谁】</b>预约成功的用户本人（userId）。<br>
 * <b>对应路由键：</b>{@code reservation.created}<br>
 * <b>通知类型：</b>1（预约成功）</p>
 *
 * <p><b>【事件发送时机】</b><br>
 * 在 {@code ReservationServiceImpl.reserve()} 方法中，预约记录插入成功后发送：
 * <pre>
 * rabbitTemplate.convertAndSend(
 *     RabbitMQConfig.EXCHANGE_NAME,
 *     RabbitMQConfig.RK_RESERVATION_CREATED,
 *     ReservationCreatedEvent.create(userId, activityId, reservation.getId()));
 * </pre>
 * </p>
 *
 * <p><b>【静态工厂 create() 与 new 的区别（为什么必须用工厂方法）】</b><br>
 * 事件对象必须携带 eventId（UUID）、eventType、occurredAt、userId 四个公共字段，
 * 缺一不可。如果每次都用 new + 手动 set：<br>
 * <pre>
 * ReservationCreatedEvent event = new ReservationCreatedEvent();
 * event.setEventId(UUID.randomUUID().toString());  // 容易忘
 * event.setEventType("RESERVATION_CREATED");          // 容易写错
 * event.setOccurredAt(LocalDateTime.now());
 * event.setUserId(userId);
 * event.setActivityId(activityId);
 * event.setReservationId(reservationId);
 * </pre>
 * 调用方很容易漏掉某个字段（比如忘了设 eventId），导致消费幂等失效。
 * 静态工厂方法把创建逻辑封装在事件类内部，调用方只需传业务参数，
 * 公共字段的生成（UUID、时间戳）和 eventType 字符串统一由事件类保证，
 * 不会漏、不会写错。</p>
 *
 * @author ming
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ReservationCreatedEvent extends BaseNotificationEvent {

    /** 事件类型（大写蛇形，用于 eventType 字段，与路由键 reservation.created 对应）。 */
    public static final String EVENT_TYPE = "RESERVATION_CREATED";

    /** 被预约的活动 ID。 */
    private Long activityId;

    /** 预约记录 ID。 */
    private Long reservationId;

    /**
     * 创建一条"预约成功"事件。
     *
     * @param userId        接收通知的用户 ID（预约成功的用户）
     * @param activityId    被预约的活动 ID
     * @param reservationId 预约记录 ID
     * @return 初始化完成的预约成功事件
     */
    public static ReservationCreatedEvent create(Long userId, Long activityId, Long reservationId) {
        ReservationCreatedEvent event = new ReservationCreatedEvent();
        event.initEvent(EVENT_TYPE, userId);
        event.setActivityId(activityId);
        event.setReservationId(reservationId);
        return event;
    }
}
