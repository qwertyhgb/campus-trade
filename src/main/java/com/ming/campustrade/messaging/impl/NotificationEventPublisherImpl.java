package com.ming.campustrade.messaging.impl;

import com.ming.campustrade.config.RabbitMQConfig;
import com.ming.campustrade.event.ActivityReviewedEvent;
import com.ming.campustrade.event.ActivityUpcomingEvent;
import com.ming.campustrade.event.BaseNotificationEvent;
import com.ming.campustrade.event.ReservationCanceledEvent;
import com.ming.campustrade.event.ReservationCreatedEvent;
import com.ming.campustrade.event.WaitlistJoinedEvent;
import com.ming.campustrade.event.WaitlistPromotedEvent;
import com.ming.campustrade.messaging.NotificationEventPublisher;

import lombok.extern.slf4j.Slf4j;

import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 通知事件发布实现类 —— 通过 RabbitTemplate 将事件发送到 RabbitMQ。
 *
 * <p><b>【为什么不需要 @Transactional？】</b><br>
 * 本类只负责发送消息，不修改数据库。发送失败不会导致业务数据不一致，
 * 所以不需要事务。业务 Service 中如果发了消息又改了数据库，
 * 事务由业务 Service 自己管理，与本类无关。</p>
 *
 * <p><b>【6 个公开方法各绑定一个固定的 routingKey】</b><br>
 * 每个方法只做一件事：调用统一的私有发送方法，传入对应的事件和 routingKey。
 * routingKey 全部引用 {@link RabbitMQConfig} 中的常量，不会写错。</p>
 *
 * <p><b>【为什么抽取统一的私有发送方法？】</b><br>
 * 6 个方法的发送流程完全一样（校验 → 设 CorrelationData → 设持久化 →
 * 设 Header → 发消息 → 打日志），如果各自写一遍，以后改发送逻辑
 * 要改 6 处，容易漏。抽取成私有方法后，所有发送逻辑集中在一处。</p>
 *
 * @author ming
 */
@Slf4j
@Component
public class NotificationEventPublisherImpl implements NotificationEventPublisher {

    /** RabbitTemplate：Spring Boot 自动配置 + RabbitMessageConverterConfig 定制后的实例。 */
    private final RabbitTemplate rabbitTemplate;

    /**
     * 构造器注入（推荐方式，保证依赖不可变、便于测试）。
     *
     * @param rabbitTemplate 自动配置 + 定制后的 RabbitTemplate
     */
    public NotificationEventPublisherImpl(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    // ==================== 6 个公开方法 ====================

    @Override
    public void publishReservationCreated(ReservationCreatedEvent event) {
        sendEvent(event, RabbitMQConfig.RK_RESERVATION_CREATED);
    }

    @Override
    public void publishReservationCanceled(ReservationCanceledEvent event) {
        sendEvent(event, RabbitMQConfig.RK_RESERVATION_CANCELED);
    }

    @Override
    public void publishWaitlistJoined(WaitlistJoinedEvent event) {
        sendEvent(event, RabbitMQConfig.RK_WAITLIST_JOINED);
    }

    @Override
    public void publishWaitlistPromoted(WaitlistPromotedEvent event) {
        sendEvent(event, RabbitMQConfig.RK_WAITLIST_PROMOTED);
    }

    @Override
    public void publishActivityReviewed(ActivityReviewedEvent event) {
        sendEvent(event, RabbitMQConfig.RK_ACTIVITY_REVIEWED);
    }

    @Override
    public void publishActivityUpcoming(ActivityUpcomingEvent event) {
        sendEvent(event, RabbitMQConfig.RK_ACTIVITY_UPCOMING);
    }

    // ==================== 统一的私有发送方法 ====================

    /**
     * 发送事件到 RabbitMQ 的统一方法。
     *
     * <p><b>【发送流程】</b></p>
     * <ol>
     *   <li>校验事件对象：不能为 null、eventId 不能为空、eventType 不能为空、userId 不能为 null</li>
     *   <li>创建 CorrelationData：用 eventId 作为关联 ID，后续确认回调中可以定位是哪条消息</li>
     *   <li>调用 convertAndSend：RabbitTemplate 自动把事件对象转 JSON（通过 Jackson2JsonMessageConverter）</li>
     *   <li>设置消息持久化：Exchange + Queue + Message 三者都持久化，重启不丢消息</li>
     *   <li>设置消息 Header：messageId 和 eventType，消费者可以根据 eventType 判断反序列化目标</li>
     *   <li>打印发送日志：eventId、eventType、routingKey</li>
     * </ol>
     *
     * @param event      事件对象（必须继承 BaseNotificationEvent）
     * @param routingKey 路由键（引用 RabbitMQConfig 中的常量）
     * @param <T>        事件类型，限定为 BaseNotificationEvent 的子类
     */
    private <T extends BaseNotificationEvent> void sendEvent(T event, String routingKey) {
        // ===== 1. 校验事件对象 =====
        // 防御性校验：避免 null 事件或缺少关键字段的消息被发送到 RabbitMQ，
        // 消费者收到残缺消息后处理失败，排查起来非常困难。
        if (event == null) {
            // 这里不能只记录日志后返回，否则业务代码会误以为消息发送成功，
            // 实际上通知事件已经被静默丢弃。参数错误属于调用方编程错误，
            // 直接抛出异常能让问题在开发和测试阶段尽早暴露。
            throw new IllegalArgumentException("通知事件不能为 null");
        }
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            throw new IllegalArgumentException("通知事件的 eventId 不能为空");
        }
        if (event.getEventType() == null || event.getEventType().isBlank()) {
            throw new IllegalArgumentException("通知事件的 eventType 不能为空，eventId=" + event.getEventId());
        }
        if (event.getUserId() == null) {
            throw new IllegalArgumentException("通知事件的 userId 不能为空，eventId=" + event.getEventId());
        }

        // ===== 2. 创建 CorrelationData（用 eventId 作为关联 ID） =====
        // CorrelationData 的作用：
        //   - 消息发送时，RabbitTemplate 把它和消息一起发给 RabbitMQ
        //   - RabbitMQ 返回确认结果时，会把 CorrelationData 原样传回给 ConfirmCallback
        //   - 这样在回调中就能通过 correlationData.getId() 拿到 eventId，定位到具体消息
        //   - 后续排查消息链路问题时，通过 eventId 可以串联"生产者日志 → 消息 → 消费者日志"
        CorrelationData correlationData = new CorrelationData(event.getEventId());

        // ===== 3. 发送消息 =====
        // rabbitTemplate.convertAndSend(exchange, routingKey, message, messagePostProcessor, correlationData)
        //   exchange：RabbitMQ 交换机名称
        //   routingKey：路由键，决定消息被投递到哪个队列
        //   message：事件对象，Jackson2JsonMessageConverter 自动转为 JSON
        //   messagePostProcessor：消息后处理器，用于设置消息属性（持久化、Header 等）
        //   correlationData：消息关联数据，用于确认回调
        //
        // 注意：消息发送后，RabbitMQ 会异步回调 ConfirmCallback 告知发送结果，
        //       但 convertAndSend 方法本身会立即返回，不阻塞等待确认。
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_NAME,
                routingKey,
                event,
                message -> {
                    // ===== 4. 设置消息持久化 =====
                    // MessageDeliveryMode.PERSISTENT = 2：消息写入磁盘，RabbitMQ 重启不丢失
                    // 配合 Exchange 持久化 + Queue 持久化，三者全持久化才是完整保障
                    message.getMessageProperties().setDeliveryMode(
                            org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);

                    // ===== 5. 设置消息 Header =====
                    // messageId：使用 eventId，方便在 RabbitMQ 管理界面定位消息
                    // eventType：消费者可以根据这个 Header 判断应该反序列化为哪种事件
                    //           （因为 6 种事件共用一个队列，消费者需要区分事件类型）
                    message.getMessageProperties().setMessageId(event.getEventId());
                    message.getMessageProperties().setHeader("eventType", event.getEventType());
                    return message;
                },
                correlationData);

        // ===== 6. 打印发送日志 =====
        // 日志包含 eventId、eventType、routingKey，方便消息链路追踪
        // convertAndSend 调用完成只代表消息已经提交给 RabbitTemplate，
        // 是否到达 Exchange 要等待 ConfirmCallback 的异步确认结果。
        log.info("事件已提交发送：eventId={}, eventType={}, routingKey={}, userId={}",
                event.getEventId(), event.getEventType(), routingKey, event.getUserId());
    }
}
