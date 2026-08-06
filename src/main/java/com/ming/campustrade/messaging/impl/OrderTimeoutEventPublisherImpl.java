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
 * 订单超时事件发布实现类 —— 通过 RabbitTemplate 把超时检查事件发送到 RabbitMQ。
 *
 * <p><b>【这个类的职责是什么？】</b><br>
 * 用户下单后 30 分钟内未支付，订单应该被自动取消。实现方式不是"定时扫描数据库"，
 * 而是"下单时发一条消息到延迟队列，30 分钟到期后消息自动出来，再去取消订单"。
 * 本类就是负责"发这条消息"的角色，真正取消订单的是消费端 {@link com.ming.campustrade.messaging.consumer.OrderTimeoutMessageConsumer}。</p>
 *
 * <p><b>【发送路径】</b><br>
 * RabbitTemplate → order.timeout.delay.exchange → order.timeout.delay.queue
 * （利用 TTL 等待 30 分钟，到期后经死信机制转发）→ order.timeout.exchange
 * → order.timeout.queue → 消费者。</p>
 *
 * <p><b>【为什么不需要 @Transactional？】</b><br>
 * 本类只负责发送消息，不修改数据库，发送失败不会造成数据不一致，所以不需要事务。
 * 订单 Service 里"更新订单状态 + 发消息"的整体一致性由业务 Service 自己的事务管理。</p>
 *
 * <p><b>【为什么消息要持久化？】</b><br>
 * 交换机（Exchange）、队列（Queue）、消息（Message）三者全部持久化，
 * RabbitMQ 重启后，尚未到期的超时消息仍然保留，不会丢失，超时取消逻辑依然可靠。</p>
 *
 * @author ming
 */
@Slf4j
@Component
public class OrderTimeoutEventPublisherImpl implements OrderTimeoutEventPublisher {

    /**
     * RabbitTemplate：与 RabbitMQ 通信的模板对象，本类用它来发送消息。
     *
     * <p><b>【它是什么？】</b><br>
     * 就像 JdbcTemplate 帮你简化数据库操作一样，RabbitTemplate 封装了
     * 建立连接、序列化对象、发送消息等繁琐细节。本类唯一的核心动作
     * rabbitTemplate.convertAndSend(...) 就是通过它完成的。</p>
     *
     * <p><b>【为什么用 final？】</b><br>
     * final 表示该字段一旦赋值就不能被替换，保证这个类从始至终
     * 都使用同一个 RabbitTemplate，避免运行中被意外换成别的实例。</p>
     *
     * <p><b>【它从哪来？】</b><br>
     * 不是 new 出来的，而是 Spring 启动时自动创建好，再通过下面的构造器
     * 注入进来（依赖注入），这也是它没有 setter 方法的原因。</p>
     */
    private final RabbitTemplate rabbitTemplate;

    /**
     * 构造器注入（推荐方式，保证依赖不可变、便于测试）。
     *
     * @param rabbitTemplate 自动配置 + 定制后的 RabbitTemplate
     */
    public OrderTimeoutEventPublisherImpl(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(OrderTimeoutEvent event) {
        // ===== 1. 防御性校验 =====
        // 防止调用方传入 null 事件或缺关键字段的事件。如果残缺消息进入 RabbitMQ，
        // 消费者反序列化或处理时会失败，排查起来非常困难。
        // 参数错误属于调用方编程错误，直接抛异常让问题在开发阶段尽早暴露，
        // 而不是静默丢弃消息，让业务代码误以为发送成功。
        if (event == null || event.getEventId() == null || event.getOrderNo() == null) {
            throw new IllegalArgumentException("订单超时事件不能为空且必须包含 eventId、orderNo");
        }

        // ===== 2. 创建 CorrelationData（用 eventId 作为关联 ID） =====
        // CorrelationData 的作用：
        //   - 发送消息时，RabbitTemplate 把它和消息一起发给 RabbitMQ
        //   - RabbitMQ 返回确认结果时，会把 CorrelationData 原样传回给 ConfirmCallback
        //   - 回调中通过 correlationData.getId() 就能拿到 eventId，定位到具体是哪条消息
        //   - 排查问题时，通过 eventId 可以串联"生产者日志 → 消息 → 消费者日志"整条链路
        CorrelationData correlationData = new CorrelationData(event.getEventId());

        // ===== 3. 发送消息 =====
        // rabbitTemplate.convertAndSend(exchange, routingKey, message, messagePostProcessor, correlationData)
        //   exchange：交换机名称。这里发往延迟交换机（order.timeout.delay.exchange），
        //             而不是直接发给消费者队列，是为了借助 TTL 实现"30 分钟后触发"
        //   routingKey：路由键。消息进入延迟交换机后，交换机根据它把消息投递到延迟队列
        //   message：事件对象，Jackson2JsonMessageConverter 自动序列化为 JSON
        //   messagePostProcessor：消息后处理器，在真正发出前设置消息属性（持久化、Header 等）
        //   correlationData：关联数据，用于异步确认回调
        //
        // 注意：convertAndSend 本身会立即返回，不阻塞等待 RabbitMQ 的确认结果，
        //       确认结果是异步回调，所以要排查发送是否成功需看 ConfirmCallback。
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_TIMEOUT_DELAY_EXCHANGE_NAME,
                RabbitMQConfig.RK_ORDER_TIMEOUT,
                event,
                message -> {
                    // ===== 4. 设置消息持久化 =====
                    // MessageDeliveryMode.PERSISTENT = 2：消息写入磁盘，RabbitMQ 重启不丢失。
                    // 配合 Exchange 持久化 + Queue 持久化，三者全部持久化才是完整保障。
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);

                    // ===== 5. 设置消息 Header =====
                    // messageId：使用 eventId，方便在 RabbitMQ 管理界面按 ID 定位消息
                    // eventType：标明消息用途，消费者据此确认消息类型，便于排查和防御性校验
                    message.getMessageProperties().setMessageId(event.getEventId());
                    message.getMessageProperties().setHeader("eventType", event.getEventType());
                    return message;
                },
                correlationData);

        // ===== 6. 打印发送日志 =====
        // 日志包含 eventId、orderId、orderNo 和延迟时间，方便消息链路追踪。
        // 这里打印"已发送"只代表消息已提交给 RabbitTemplate，
        // 是否真正到达 Exchange 需等待 ConfirmCallback 的异步确认结果。
        log.info("订单超时事件已发送：eventId={}, orderId={}, orderNo={}, delay={}分钟",
                event.getEventId(), event.getOrderId(), event.getOrderNo(),
                RabbitMQConfig.ORDER_TIMEOUT_DELAY_MILLIS / 60_000);
    }
}
