package com.ming.campustrade.messaging;

import com.ming.campustrade.event.ActivityReviewedEvent;
import com.ming.campustrade.event.ActivityUpcomingEvent;
import com.ming.campustrade.event.ReservationCanceledEvent;
import com.ming.campustrade.event.ReservationCreatedEvent;
import com.ming.campustrade.event.WaitlistJoinedEvent;
import com.ming.campustrade.event.WaitlistPromotedEvent;

/**
 * 通知事件发布接口 —— 所有业务事件的 RabbitMQ 发送入口。
 *
 * <p><b>【为什么设计成 6 个明确的方法而不是 1 个通用方法？】</b></p>
 * <ul>
 *   <li>调用方不需要知道 routingKey，方法名已经表达了"发什么事件"</li>
 *   <li>每种事件绑定固定的 routingKey，不会写错</li>
 *   <li>方法参数就是对应的事件类型，不会传错事件对象</li>
 *   <li>后续替换消息中间件（如换 RocketMQ）时，只需改实现类，接口不变</li>
 *   <li>查找引用时能清楚知道"哪些地方发了预约成功事件"</li>
 * </ul>
 *
 * <p><b>【为什么不设计成 publish(String routingKey, Object event) 这种通用方法？】</b><br>
 * 通用方法虽然灵活，但容易出错：调用方可能传错 routingKey（如预约成功传了取消的 key），
 * 或者传错事件类型（如传了预订事件但 routingKey 是候补的）。编译时不会报错，
 * 运行时消息发错地方，排查起来很麻烦。6 个明确的方法在编译期就杜绝了这类错误。</p>
 *
 * @author ming
 */
public interface NotificationEventPublisher {

    /**
     * 发布预约成功事件。
     *
     * @param event 预约成功事件（含 eventId、userId、activityId、reservationId）
     */
    void publishReservationCreated(ReservationCreatedEvent event);

    /**
     * 发布取消预约事件。
     *
     * @param event 取消预约事件（含 eventId、userId、activityId、reservationId）
     */
    void publishReservationCanceled(ReservationCanceledEvent event);

    /**
     * 发布加入候补事件。
     *
     * @param event 加入候补事件（含 eventId、userId、activityId、waitlistId）
     */
    void publishWaitlistJoined(WaitlistJoinedEvent event);

    /**
     * 发布候补补位成功事件。
     *
     * @param event 候补补位事件（含 eventId、userId、activityId、waitlistId）
     */
    void publishWaitlistPromoted(WaitlistPromotedEvent event);

    /**
     * 发布活动审核结果事件。
     *
     * @param event 活动审核事件（含 eventId、userId、activityId、passed、rejectReason）
     */
    void publishActivityReviewed(ActivityReviewedEvent event);

    /**
     * 发布活动即将开始事件。
     *
     * @param event 活动即将开始事件（含 eventId、userId、activityId、activityTitle、startTime）
     */
    void publishActivityUpcoming(ActivityUpcomingEvent event);
}
