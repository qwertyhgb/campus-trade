package com.ming.campustrade.messaging.consumer;

import java.io.IOException;

import com.ming.campustrade.config.RabbitMQConfig;
import com.ming.campustrade.event.OrderTimeoutEvent;
import com.ming.campustrade.service.OrderService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;
import tools.jackson.databind.ObjectMapper;

/**
 * 订单超时消息消费者。
 *
 * <p><b>完整链路：</b></p>
 * <pre>
 * 创建订单
 *     ↓ afterCommit 发送消息
 * order.timeout.delay.exchange
 *     ↓
 * order.timeout.delay.queue（TTL 30 分钟）
 *     ↓ TTL 到期后死信转发
 * order.timeout.exchange → order.timeout.queue
 *     ↓
 * 重新查订单 + UPDATE ... WHERE status = PENDING
 *     ↓
 * PENDING → CANCELED，同时 LOCKED 商品 → ON_SALE
 * </pre>
 *
 * <p><b>为什么重复消息不会造成重复取消？</b><br>
 * RabbitMQ 是至少一次投递，网络重试或消费者重启都可能让同一事件到达多次。
 * 本消费者不依赖内存变量去重，而是让数据库条件更新承担最终幂等保证：
 * 第一次更新成功，后续消息再执行 WHERE status = PENDING 时影响行数为 0，直接确认即可。</p>
 *
 * @author ming
 */
@Slf4j
@Component
public class OrderTimeoutMessageConsumer {

    /** 记录订单超时消息临时失败次数的自定义 Header。 */
    private static final String RETRY_COUNT_HEADER = "x-order-timeout-retry-count";

    private final OrderService orderService;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;

    public OrderTimeoutMessageConsumer(OrderService orderService,
                                       ObjectMapper objectMapper,
                                       RabbitTemplate rabbitTemplate) {
        this.orderService = orderService;
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 监听订单超时队列，并使用手动 ack。
     *
     * <p>只有订单服务事务完成后才 ack；如果数据库临时失败，消息会进入短暂延迟重试队列；
     * 如果消息格式错误或重试耗尽，则通过队列 DLX 进入订单专用死信队列。</p>
     */
    @RabbitListener(queues = RabbitMQConfig.ORDER_TIMEOUT_QUEUE_NAME, ackMode = "MANUAL")
    public void onMessage(Message message,
                           Channel channel,
                           @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        String eventId = null;
        try {
            String headerEventType = message.getMessageProperties().getHeader("eventType");
            if (!OrderTimeoutEvent.EVENT_TYPE.equals(headerEventType)) {
                throw new IllegalArgumentException("订单超时消息 eventType Header 不正确");
            }

            OrderTimeoutEvent event = objectMapper.readValue(message.getBody(), OrderTimeoutEvent.class);
            validateEvent(event, headerEventType);
            eventId = event.getEventId();

            // 重新查询数据库，不能相信消息中可能已经过时的订单快照。
            // 返回 false 也算正常处理：订单可能已经支付/确认、取消，或本来就不存在。
            boolean cancelled = orderService.autoCancelTimeoutOrderByNo(event.getOrderNo());

            // 事务方法返回后，订单状态和商品状态已经提交，才确认消息。
            channel.basicAck(deliveryTag, false);
            log.info("订单超时消息处理完成：eventId={}, orderNo={}, cancelled={}",
                    eventId, event.getOrderNo(), cancelled);
        } catch (IllegalArgumentException e) {
            // 格式错误属于永久失败，重新投递也不会变好；requeue=false 会进入订单专用 DLX。
            log.error("订单超时消息格式非法，转入死信队列：eventId={}, 原因={}",
                    eventId, e.getMessage());
            channel.basicNack(deliveryTag, false, false);
        } catch (Exception e) {
            // 数据库短暂不可用等临时故障，采用有限次数和指数退避重试。
            retryOrDeadLetter(message, channel, deliveryTag, eventId, e);
        }
    }

    /**
     * 临时故障重试：5 秒、10 秒、20 秒后重试，超过 3 次进入死信队列。
     */
    private void retryOrDeadLetter(Message message,
                                   Channel channel,
                                   long deliveryTag,
                                   String eventId,
                                   Exception cause) throws IOException {
        int currentRetryCount = readRetryCount(message);
        if (currentRetryCount >= RabbitMQConfig.ORDER_TIMEOUT_MAX_RETRY_COUNT) {
            log.error("订单超时消息重试耗尽，转入死信队列：eventId={}, retryCount={}, 原因={}",
                    eventId, currentRetryCount, cause.getMessage(), cause);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        int nextRetryCount = currentRetryCount + 1;
        long delayMillis = RabbitMQConfig.ORDER_TIMEOUT_BASE_RETRY_DELAY_MILLIS
                * (1L << (nextRetryCount - 1));
        try {
            // 保留原始订单事件 JSON 和 Header，只增加重试计数、过期时间。
            message.getMessageProperties().setHeader(RETRY_COUNT_HEADER, nextRetryCount);
            message.getMessageProperties().setExpiration(String.valueOf(delayMillis));
            rabbitTemplate.send(RabbitMQConfig.ORDER_TIMEOUT_RETRY_EXCHANGE_NAME,
                    RabbitMQConfig.RK_ORDER_TIMEOUT_RETRY, message);

            // 重试消息交给 RabbitTemplate 后再 ack 原消息，避免先 ack 导致消息丢失。
            channel.basicAck(deliveryTag, false);
            log.warn("订单超时消息已安排重试：eventId={}, retryCount={}/{}, delay={}ms",
                    eventId, nextRetryCount, RabbitMQConfig.ORDER_TIMEOUT_MAX_RETRY_COUNT, delayMillis);
        } catch (Exception retryException) {
            // 重试消息发送失败，保留原消息，等待 RabbitMQ 再次投递。
            log.error("订单超时重试消息发送失败，原消息重新入队：eventId={}", eventId, retryException);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /** 读取重试 Header；Header 非法时按重试耗尽处理，防止恶意消息无限循环。 */
    private int readRetryCount(Message message) {
        Object value = message.getMessageProperties().getHeader(RETRY_COUNT_HEADER);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return RabbitMQConfig.ORDER_TIMEOUT_MAX_RETRY_COUNT;
        }
    }

    /** 校验消息的关键字段，避免残缺消息进入业务层。 */
    private void validateEvent(OrderTimeoutEvent event, String headerEventType) {
        if (event == null
                || event.getEventId() == null || event.getEventId().isBlank()
                || event.getEventType() == null
                || !headerEventType.equals(event.getEventType())
                || event.getOrderId() == null
                || event.getOrderNo() == null || event.getOrderNo().isBlank()) {
            throw new IllegalArgumentException("订单超时消息缺少或错误的关键字段");
        }
    }
}
