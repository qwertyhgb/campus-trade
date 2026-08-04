package com.ming.campustrade.messaging.impl;

import com.ming.campustrade.config.RabbitMQConfig;
import com.ming.campustrade.event.OrderTimeoutEvent;
import com.ming.campustrade.messaging.OrderTimeoutEventPublisher;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 订单超时事件发布实现。
 *
 * <p><b>发送路径：</b><br>
 * RabbitTemplate → order.timeout.delay.exchange → order.timeout.delay.queue
 * （等待 30 分钟）→ order.timeout.exchange → order.timeout.queue → 消费者。</p>
 *
 * <p>交换机、队列和消息都设置为持久化，RabbitMQ 重启后仍能保留尚未到期的超时消息。</p>
 *
 * @author ming
 */
@Slf4j
@Component
public class OrderTimeoutEventPublisherImpl implements OrderTimeoutEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public OrderTimeoutEventPublisherImpl(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(OrderTimeoutEvent event) {
        if (event == null || event.getEventId() == null || event.getOrderNo() == null) {
            throw new IllegalArgumentException("订单超时事件不能为空且必须包含 eventId、orderNo");
        }

        CorrelationData correlationData = new CorrelationData(event.getEventId());
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_TIMEOUT_DELAY_EXCHANGE_NAME,
                RabbitMQConfig.RK_ORDER_TIMEOUT,
                event,
                message -> {
                    // 持久化消息：RabbitMQ 重启后，仍未到期的超时消息不会直接丢失。
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties().setMessageId(event.getEventId());
                    message.getMessageProperties().setHeader("eventType", event.getEventType());
                    return message;
                },
                correlationData);

        log.info("订单超时事件已发送：eventId={}, orderId={}, orderNo={}, delay={}分钟",
                event.getEventId(), event.getOrderId(), event.getOrderNo(),
                RabbitMQConfig.ORDER_TIMEOUT_DELAY_MILLIS / 60_000);
    }
}
