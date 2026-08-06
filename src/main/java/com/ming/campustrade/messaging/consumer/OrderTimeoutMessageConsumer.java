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
 * 订单超时消息消费者 —— 从 RabbitMQ 队列取出超时事件，自动取消超时订单。
 *
 * <p><b>【这个类的职责是什么？】</b><br>
 * 用户下单 30 分钟未支付，订单要被自动取消、锁定的商品要恢复上架。
 * 生产端在订单创建后发出超时事件，本类就是这个事件的"最终处理人"：
 * 收到消息 → 校验类型 → 反序列化 → 重新查库 → 条件更新取消订单 → 手动确认消息。</p>
 *
 * <p><b>【完整链路：】</b></p>
 * <pre>
 * 创建订单
 *     ↓ afterCommit 发送消息
 * order.timeout.delay.exchange
 *     ↓
 * order.timeout.delay.queue（TTL 30 分钟）
 *     ↓ TTL 到期后死信转发
 * order.timeout.exchange → order.timeout.queue
 *     ↓
 * 本消费者：重新查订单 + UPDATE ... WHERE status = PENDING
 *     ↓
 * PENDING → CANCELED，同时 LOCKED 商品 → ON_SALE
 * </pre>
 *
 * <p><b>【为什么采用手动确认（ackMode = MANUAL）？】</b><br>
 * 手动确认 = "订单确实被取消且事务已提交，才告诉 RabbitMQ 删除消息"。
 * 如果使用默认的 AUTO 模式，方法不抛异常就自动确认，但"方法返回"和"事务提交"
 * 之间存在时间窗，数据库提交失败时消息已被删除，超时取消就丢了。
 * 手动确认能在事务提交之后再 ack，保证取消逻辑不丢。</p>
 *
 * <p><b>【消息处理失败的两条分叉路】</b></p>
 * <ul>
 *   <li><b>格式错误</b>（eventType 不对、字段缺失）：属于永久失败，重试多少次都不会变好，
 *       直接 basicNack(requeue=false) 送进死信队列，供人工排查</li>
 *   <li><b>临时故障</b>（数据库短暂不可用）：先送入 TTL 重试队列，
 *       按 5 秒、10 秒、20 秒指数退避重试，最多 3 次，耗尽后进死信队列</li>
 * </ul>
 *
 * <p><b>【为什么重试消息能"等待一段时间再回来"？】</b><br>
 * 重试队列配置了 x-message-ttl，消息进去后要等满这个时间，
 * 到期后经死信转发重新回到主队列 order.timeout.queue，再次被本消费者收到。
 * 这比 basicNack(requeue=true) 立刻重回队列更安全，
 * 因为数据库故障时立即重投会形成高速重试循环，打爆 MQ 和数据库。</p>
 *
 * <p><b>【为什么重复消息不会造成重复取消？】</b><br>
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

    /** 订单业务 Service：重新查库并执行条件更新取消订单（事务方法）。 */
    private final OrderService orderService;

    /** Jackson 3 ObjectMapper（与生产者消息转换器同一套），用于反序列化事件。 */
    private final ObjectMapper objectMapper;

    /**
     * RabbitTemplate：把临时失败的消息发送到延迟重试交换机。
     *
     * <p>这里不使用 basicNack(requeue=true) 立即重回队列，而是发送到 TTL 重试队列，
     * 消息等待一段时间后重新回到主队列，避免故障期间高速重试循环。</p>
     */
    private final RabbitTemplate rabbitTemplate;

    /**
     * 构造器注入（推荐方式，保证依赖不可变、便于测试）。
     *
     * @param orderService   订单业务 Service
     * @param objectMapper   Jackson 3 ObjectMapper
     * @param rabbitTemplate RabbitTemplate（用于发送重试消息）
     */
    public OrderTimeoutMessageConsumer(OrderService orderService,
                                       ObjectMapper objectMapper,
                                       RabbitTemplate rabbitTemplate) {
        this.orderService = orderService;
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 监听订单超时队列，处理超时消息（手动确认模式）。
     *
     * <p><b>【方法参数说明】</b></p>
     * <ul>
     *   <li>{@code Message}：RabbitMQ 原始消息（含消息体 body 和消息属性 headers），
     *       不直接接收事件对象 —— 需要先校验 Header 再从 JSON 反序列化</li>
     *   <li>{@code Channel}：与 RabbitMQ 的通道，手动确认/拒绝都要通过它调用</li>
     *   <li>{@code deliveryTag}：<b>投递标签</b>，RabbitMQ 给每条投递的消息分配的递增序号，
     *       确认/拒绝时用它指明是哪条消息（同一通道内唯一）</li>
     * </ul>
     *
     * <p><b>【处理结果的三种去向】</b></p>
     * <ul>
     *   <li>成功（含"订单已不存在/已处理"）→ basicAck：消息从队列移除</li>
     *   <li>格式错误 → basicNack(requeue=false)：进入死信队列</li>
     *   <li>临时故障 → 送入 TTL 重试队列，指数退避最多 3 次</li>
     * </ul>
     *
     * @param message     原始消息
     * @param channel     RabbitMQ 通道
     * @param deliveryTag 投递标签（确认/拒绝时指明是哪条消息）
     * @throws IOException 与 RabbitMQ 通信失败时抛出
     */
    @RabbitListener(queues = RabbitMQConfig.ORDER_TIMEOUT_QUEUE_NAME, ackMode = "MANUAL")
    public void onMessage(Message message,
                           Channel channel,
                           @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        // 先声明为 null，消息在解析成功前出错时，日志也能打出 eventId 的当前值（null）。
        String eventId = null;
        try {
            // ===== 1. 校验 eventType Header =====
            // 先确认消息类型符合预期（防止其他类型的消息误入本队列），
            // 再决定如何反序列化。Header 与期望不符说明消息异常，按格式错误处理。
            String headerEventType = message.getMessageProperties().getHeader("eventType");
            if (!OrderTimeoutEvent.EVENT_TYPE.equals(headerEventType)) {
                throw new IllegalArgumentException("订单超时消息 eventType Header 不正确");
            }

            // ===== 2. 反序列化：JSON → 事件对象 =====
            // 消息体是生产端序列化后的 JSON，这里用同一套 Jackson 转换器还原成对象。
            OrderTimeoutEvent event = objectMapper.readValue(message.getBody(), OrderTimeoutEvent.class);

            // ===== 3. 校验关键字段 =====
            // 反序列化只能保证 JSON 语法正确，不能保证业务字段完整；
            // 缺 eventId 无法追踪、缺 orderNo 无法定位订单，残缺消息必须在此拦截。
            validateEvent(event, headerEventType);
            eventId = event.getEventId();

            // ===== 4. 重新查询数据库，执行条件更新 =====
            // 不能相信消息里携带的订单信息：从下单到消息到达已过 30 分钟，
            // 订单可能已被支付/确认/取消，所以必须以数据库当前状态为准重新查询。
            // 返回 false 也算正常处理：订单可能已经支付/确认、取消，或本来就不存在。
            boolean cancelled = orderService.autoCancelTimeoutOrderByNo(event.getOrderNo());

            // ===== 5. 事务提交后，手动确认消息 =====
            // autoCancelTimeoutOrderByNo 是 @Transactional 方法，
            // 它返回时订单状态和商品状态已经提交，此时 ack 才是安全的 ——
            // 即使 ack 之后消费者宕机，订单也已经被正确取消了。
            channel.basicAck(deliveryTag, false);
            log.info("订单超时消息处理完成：eventId={}, orderNo={}, cancelled={}",
                    eventId, event.getOrderNo(), cancelled);
        } catch (IllegalArgumentException e) {
            // ===== 格式错误分支：永久失败，进死信队列 =====
            // 格式错误的消息重试多少次结果都一样；requeue=false 表示不重回队列，
            // RabbitMQ 会按队列的死信配置把它转入订单专用死信队列，供人工排查。
            log.error("订单超时消息格式非法，转入死信队列：eventId={}, 原因={}",
                    eventId, e.getMessage());
            channel.basicNack(deliveryTag, false, false);
        } catch (Exception e) {
            // ===== 临时故障分支：有限次数 + 指数退避重试 =====
            // 数据库短暂不可用等临时故障，直接拒绝会让消息立刻重投形成风暴，
            // 所以交给 retryOrDeadLetter：先送入 TTL 重试队列，延迟后再回来。
            retryOrDeadLetter(message, channel, deliveryTag, eventId, e);
        }
    }

    /**
     * 临时故障重试：5 秒、10 秒、20 秒后重试，超过 3 次进入死信队列。
     *
     * <p><b>【指数退避公式】</b><br>
     * 第 1 次重试等待 5s × 2⁰ = 5s，第 2 次 5s × 2¹ = 10s，第 3 次 5s × 2² = 20s。
     * 间隔越来越长，给数据库留出恢复时间，同时避免短时间内反复冲击。</p>
     */
    private void retryOrDeadLetter(Message message,
                                   Channel channel,
                                   long deliveryTag,
                                   String eventId,
                                   Exception cause) throws IOException {
        // ===== 1. 判断重试是否已耗尽 =====
        // 读取消息头里记录的历史重试次数，达到上限就不再重试，
        // 把消息送进死信队列，避免无限循环占用资源。
        int currentRetryCount = readRetryCount(message);
        if (currentRetryCount >= RabbitMQConfig.ORDER_TIMEOUT_MAX_RETRY_COUNT) {
            log.error("订单超时消息重试耗尽，转入死信队列：eventId={}, retryCount={}, 原因={}",
                    eventId, currentRetryCount, cause.getMessage(), cause);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        // ===== 2. 计算本次重试的等待时间（指数退避） =====
        // 1L << n 表示 2 的 n 次方：第 1 次 5s、第 2 次 10s、第 3 次 20s。
        int nextRetryCount = currentRetryCount + 1;
        long delayMillis = RabbitMQConfig.ORDER_TIMEOUT_BASE_RETRY_DELAY_MILLIS
                * (1L << (nextRetryCount - 1));
        try {
            // ===== 3. 发送到重试交换机 =====
            // 保留原始订单事件 JSON 和 Header，只增加重试计数、设置过期时间（TTL）。
            // 消息到达重试队列后要等 delayMillis 毫秒，到期后经死信转发回到主队列，再次被消费。
            message.getMessageProperties().setHeader(RETRY_COUNT_HEADER, nextRetryCount);
            message.getMessageProperties().setExpiration(String.valueOf(delayMillis));
            rabbitTemplate.send(RabbitMQConfig.ORDER_TIMEOUT_RETRY_EXCHANGE_NAME,
                    RabbitMQConfig.RK_ORDER_TIMEOUT_RETRY, message);

            // ===== 4. 确认原消息 =====
            // 重试消息交给 RabbitTemplate 后再 ack 原消息，避免先 ack 导致消息丢失。
            channel.basicAck(deliveryTag, false);
            log.warn("订单超时消息已安排重试：eventId={}, retryCount={}/{}, delay={}ms",
                    eventId, nextRetryCount, RabbitMQConfig.ORDER_TIMEOUT_MAX_RETRY_COUNT, delayMillis);
        } catch (Exception retryException) {
            // ===== 5. 重试消息发送失败 =====
            // 不 ack 也不拒收（requeue=true），让 RabbitMQ 稍后重新投递原消息，
            // 再走一遍重试流程，而不是直接把消息丢掉。
            log.error("订单超时重试消息发送失败，原消息重新入队：eventId={}", eventId, retryException);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /**
     * 读取重试 Header，返回当前已重试次数。
     *
     * <p>正常没有该 Header 说明这是第一次失败，返回 0（从头开始重试）；
     * 如果 Header 存在但内容不是数字，说明消息被篡改或异常，
     * 直接返回最大重试次数，让该消息进入死信队列而不是无限循环重试。</p>
     */
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

    /**
     * 校验消息的关键字段，避免残缺消息进入业务层。
     *
     * <p>反序列化只能保证 JSON 语法正确，不能保证业务字段完整：
     * 缺 eventId 无法追踪消息、缺 orderNo 无法定位订单。
     * Header 里的 eventType 与事件对象内的 eventType 也必须一致，
     * 否则说明消息组装有问题，必须在进入订单取消逻辑前拦截下来。</p>
     */
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
