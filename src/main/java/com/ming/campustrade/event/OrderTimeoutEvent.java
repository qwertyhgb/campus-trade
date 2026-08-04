package com.ming.campustrade.event;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 订单超时检查事件。
 *
 * <p><b>它和站内通知事件有什么不同？</b><br>
 * 站内通知事件的目标是“告诉用户发生了什么”，通常继承通知事件基类并携带 userId；
 * 本事件的目标是“提醒订单服务检查状态”，不需要写入 notification 表，
 * 所以单独建类，避免把业务动作消息和展示通知消息混在一起。</p>
 *
 * <p><b>为什么 eventId 使用订单号生成而不是随机 UUID？</b><br>
 * 同一个订单只应该有一个超时检查事件。即使因为网络重试、重复发送导致消息出现多份，
 * 它们携带的订单号也相同，消费者会通过状态条件更新保证只有第一条能真正取消订单。</p>
 *
 * @author ming
 */
@Data
public class OrderTimeoutEvent {

    /** 消息事件唯一 ID，用于日志追踪和重复消息排查。 */
    private String eventId;

    /** 事件类型，消费者据此确认消息用途。 */
    private String eventType;

    /** 事件创建时间，不等于消费者实际收到时间。 */
    private LocalDateTime occurredAt;

    /** 订单主键，方便日志和排查。 */
    private Long orderId;

    /** 订单号，消费者按它执行唯一且安全的条件更新。 */
    private String orderNo;

    /** 事件类型常量。 */
    public static final String EVENT_TYPE = "ORDER_TIMEOUT";

    /**
     * 创建订单超时事件。
     *
     * @param orderId 订单主键
     * @param orderNo 订单号
     * @return 初始化完成的超时事件
     */
    public static OrderTimeoutEvent create(Long orderId, String orderNo) {
        if (orderId == null || orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("订单超时事件缺少 orderId 或 orderNo");
        }

        OrderTimeoutEvent event = new OrderTimeoutEvent();
        event.eventId = "ORDER_TIMEOUT_" + orderNo;
        event.eventType = EVENT_TYPE;
        event.occurredAt = LocalDateTime.now();
        event.orderId = orderId;
        event.orderNo = orderNo;
        return event;
    }
}
