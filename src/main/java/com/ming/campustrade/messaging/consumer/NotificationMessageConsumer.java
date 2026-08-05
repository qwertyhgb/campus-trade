package com.ming.campustrade.messaging.consumer;

import java.io.IOException;

import com.ming.campustrade.config.RabbitMQConfig;
import com.ming.campustrade.event.ActivityReviewedEvent;
import com.ming.campustrade.event.ActivityUpcomingEvent;
import com.ming.campustrade.event.BaseNotificationEvent;
import com.ming.campustrade.event.ReservationCanceledEvent;
import com.ming.campustrade.event.ReservationCreatedEvent;
import com.ming.campustrade.event.WaitlistJoinedEvent;
import com.ming.campustrade.event.WaitlistPromotedEvent;
import com.ming.campustrade.service.NotificationService;

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
 * 站内通知消息消费者 —— 从 RabbitMQ 队列取出事件，异步写入通知表。
 *
 * <p><b>【一次完整的消费流程（文字描述图）】</b></p>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │ RabbitMQ 队列 (notification.queue)                            │
 * │ 存着 6 种事件消息（预约成功/取消/候补加入/候补补位/活动审核/活动即将开始）│
 * └──────────────────────────┬───────────────────────────────────┘
 *                            │ RabbitMQ 推送给消费者（push 模式）
 *                            ▼
 * ┌──────────────────────────────────────────────────────────────┐
 * │ 本消费者 (@RabbitListener)                                      │
 * │  ① 取消息 + 读 eventType Header → 确定具体事件类型               │
 * │  ② 用 Jackson 把 JSON 反序列化成具体事件对象                     │
 * │  ③ 调 NotificationService.processIfNew()（事务：消费记录+通知）   │
 * │  ④ 手动确认 basicAck / 拒绝 basicNack                           │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>【为什么使用 @RabbitListener？】</b><br>
 * Spring AMQP 的注解式消费者：标注后，Spring 自动为当前类创建一个
 * MessageListenerContainer（消息监听容器），负责建立连接、启动监听线程、
 * 从指定队列拉取/接收消息，并调用本方法。开发者不需要手动写
 * Channel 监听代码，只需要关心"收到消息后怎么处理"。</p>
 *
 * <p><b>【为什么当前采用手动确认（ackMode = "MANUAL"）？】</b><br>
 * 手动确认 = "我处理完并落库了，才告诉 RabbitMQ 可以删消息"。
 * 对比三种模式：</p>
 * <ul>
 *   <li>AUTO（默认）：方法不抛异常就自动 ack —— 但"方法不抛异常"≠"数据库已提交"，
 *       两者之间隔着时间窗，可能丢消息</li>
 *   <li>NONE：RabbitMQ 认为投递即成功（自动 ack）—— 消息必丢，仅用于不重要的场景</li>
 *   <li>MANUAL（本类）：处理成功才 ack —— 通知是重要业务数据，不能丢</li>
 * </ul>
 *
 * <p><b>【为什么不能直接把消息反序列化成 BaseNotificationEvent？】</b><br>
 * BaseNotificationEvent 是抽象父类，6 种事件共用一个队列。
 * Jackson 反序列化时看到父类并不知道该创建哪个子类（JSON 里没有类型信息字段），
 * 强行反序列化只能得到父类或直接报错。所以消费者先读 eventType Header，
 * 手动选择具体子类再反序列化 —— 逻辑直观、容易排查。</p>
 *
 * <p><b>【什么是 at-least-once 投递？】</b><br>
 * RabbitMQ 保证消息"至少被投递一次"：正常情况下一次，异常情况下可能多次
 * （消费者 ack 丢失、处理超时重投、消费者重启时队列里的消息重新投递）。
 * 本消费者不依赖 RabbitMQ 做 exactly-once，而是由 NotificationService 的 eventId 唯一索引兜底：
 * 重复消息 → 幂等跳过 → 直接 ack。这就是"消费幂等"。</p>
 *
 * @author ming
 */
@Slf4j
@Component
public class NotificationMessageConsumer {

    /** 自定义消息头：记录这条消息已经经历过几次临时故障重试。 */
    private static final String RETRY_COUNT_HEADER = "x-retry-count";

    /** 通知业务 Service：幂等消费（消费记录 + 通知写入在一个事务里）。 */
    private final NotificationService notificationService;

    /** Jackson 3 ObjectMapper（与生产者消息转换器同一套），用于反序列化事件。 */
    private final ObjectMapper objectMapper;

    /**
     * RabbitTemplate：把临时失败的消息发送到延迟重试交换机。
     *
     * <p>这里不是直接 basicNack(requeue=true)，因为那会立刻重回主队列，
     * 数据库故障时可能形成高速重试循环。发送到 TTL 重试队列后，消息会等待一段时间再回来。</p>
     */
    private final RabbitTemplate rabbitTemplate;

    public NotificationMessageConsumer(NotificationService notificationService,
                                       ObjectMapper objectMapper,
                                       RabbitTemplate rabbitTemplate) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 监听通知队列，处理消息（手动确认模式）。
     *
     * <p><b>【方法参数说明】</b></p>
     * <ul>
     *   <li>{@code Message}：RabbitMQ 原始消息（含消息体 body 和消息属性 headers），
     *       不直接接收具体事件类 —— 因为 6 种事件共用一个队列，无法静态确定类型</li>
     *   <li>{@code Channel}：与 RabbitMQ 的通道，手动确认/拒绝都要通过它调用</li>
     *   <li>{@code deliveryTag}：<b>投递标签</b>，RabbitMQ 给每条投递的消息分配的
     *       递增序号，用于告诉 RabbitMQ"我确认的是哪条消息"（同一通道内唯一）</li>
     * </ul>
     *
     * <p><b>【手动确认的三种情况（重点）】</b></p>
     * <ul>
     *   <li>消费成功 → basicAck(deliveryTag, false)：消息从队列移除</li>
     *   <li>重复消息（幂等命中）→ basicAck：不写通知，直接确认移除</li>
     *   <li>临时失败 → 投递到延迟重试队列，最多重试 3 次，并采用 5/10/20 秒退避</li>
     *   <li>永远无法成功的消息或重试耗尽 → basicNack(..., false)：
     *       进入死信队列，避免毒消息或故障消息无限占用主队列</li>
     * </ul>
     *
     * @param message     原始消息
     * @param channel     RabbitMQ 通道
     * @param deliveryTag 投递标签（确认/拒绝时指明是哪条消息）
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME, ackMode = "MANUAL")
    public void onNotification(Message message, Channel channel,
                               @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        String eventId = null;
        String eventType = null;
        try {
            // ===== ① 从 Header 读取事件类型 =====
            // 生产者在发送时设置了 eventType Header（见 NotificationEventPublisherImpl）
            // 用它决定反序列化目标 —— 这是"共用一个队列 + 区分 6 种事件"的关键
            eventType = message.getMessageProperties().getHeader("eventType");
            if (eventType == null || eventType.isBlank()) {
                throw new IllegalArgumentException("消息缺少 eventType Header");
            }

            // ===== ② 按 eventType 选择具体事件类，反序列化 =====
            // 等价 SQL 没有 —— 这是纯内存操作。ObjectMapper 与生产者共用
            // Jackson 3 配置，保证序列化/反序列化完全对称。
            BaseNotificationEvent event;
            try {
                event = deserializeEvent(message, eventType);
            } catch (IOException e) {
                // JSON 解析失败通常不是临时性故障，重复投递同一份坏 JSON 也不会成功。
                // 转成 IllegalArgumentException，让下面的“非法消息”分支拒绝且不重新入队，
                // 避免坏消息在队列中无限循环。
                throw new IllegalArgumentException("消息 JSON 格式非法", e);
            }

            // 生产者发送时虽然已经校验过事件，但消费者不能完全信任外部消息。
            // 这里再次校验，防止缺少关键字段的消息进入数据库并触发无限重试。
            validateEvent(event, eventType);
            eventId = event.getEventId();

            // ===== ③ 幂等消费（事务：消费记录 + 通知写入） =====
            // true  = 第一次消费，通知已生成
            // false = 重复消息，已跳过（幂等命中）
            boolean isNew = notificationService.processIfNew(event, RabbitMQConfig.QUEUE_NAME);

            // ===== ④ 手动确认（消息从队列移除） =====
            // 重要：此时数据库事务已经提交（processIfNew 返回），先落库再 ack，
            // 即使 ack 之后 JVM 崩溃，通知也已经持久化了，不会丢。
            channel.basicAck(deliveryTag, false);
            log.info("消息消费完成：eventId={}, eventType={}, isNew={}", eventId, eventType, isNew);

        } catch (IllegalArgumentException e) {
            // ===== ⑤ 无法修复的消息：拒绝且不重新入队 =====
            // 格式错误、未知事件类型 —— 无论重试多少次都会失败（毒消息）。
            // 如果重新入队会无限循环，拖垮队列。所以直接拒绝且不重新入队；
            // 主队列已经配置 DLX，RabbitMQ 会把它转入死信队列供人工排查。
            log.error("消息格式非法，拒绝且不重新入队：eventType={}, eventId={}, 原因：{}",
                    eventType, eventId, e.getMessage());
            channel.basicNack(deliveryTag, false, false);

        } catch (Exception e) {
            // ===== ⑥ 临时性失败：有限次数 + 延迟重试 =====
            // 数据库短暂不可用、网络抖动等问题，稍后重试可能成功；
            // 但不能无限 requeue=true，否则故障期间消息会在主队列中高速循环。
            retryOrDeadLetter(message, channel, deliveryTag, eventId, eventType, e);
        }
    }

    /**
     * 临时性失败的处理策略：重试 3 次，仍失败则进入死信队列。
     *
     * <p><b>为什么要先发送重试消息，再确认原消息？</b><br>
     * 先发送、后 ack 可以避免"原消息已经删除，但重试消息还没发出去"造成消息丢失。
     * 如果发送重试消息本身失败，保留原消息并 requeue，让 RabbitMQ 再次投递。</p>
     */
    private void retryOrDeadLetter(Message message,
                                   Channel channel,
                                   long deliveryTag,
                                   String eventId,
                                   String eventType,
                                   Exception cause) throws IOException {
        int currentRetryCount = readRetryCount(message);
        if (currentRetryCount >= RabbitMQConfig.MAX_RETRY_COUNT) {
            log.error("消息重试次数耗尽，转入死信队列：eventType={}, eventId={}, retryCount={}, 原因：{}",
                    eventType, eventId, currentRetryCount, cause.getMessage(), cause);
            // requeue=false + 主队列 DLX 配置 = RabbitMQ 自动转入死信队列。
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        int nextRetryCount = currentRetryCount + 1;
        long delayMillis = calculateRetryDelay(nextRetryCount);
        try {
            // 复用原消息体，只增加重试次数和过期时间两个属性。
            // 事件 JSON、eventType Header、messageId 等信息都必须保留，
            // 否则消息回来后消费者无法反序列化或无法做幂等。
            message.getMessageProperties().setHeader(RETRY_COUNT_HEADER, nextRetryCount);
            message.getMessageProperties().setExpiration(String.valueOf(delayMillis));
            rabbitTemplate.send(RabbitMQConfig.RETRY_EXCHANGE_NAME,
                    RabbitMQConfig.RK_RETRY, message);

            // 重试消息已经交给 RabbitTemplate 后，确认主队列原消息。
            channel.basicAck(deliveryTag, false);
            log.warn("消息已安排延迟重试：eventType={}, eventId={}, retryCount={}/{}, delay={}ms",
                    eventType, eventId, nextRetryCount, RabbitMQConfig.MAX_RETRY_COUNT, delayMillis);
        } catch (Exception retryException) {
            // 重试消息发送失败，不能 ack 原消息，否则会丢消息。
            log.error("延迟重试消息发送失败，保留原消息重新入队：eventType={}, eventId={}, 原因：{}",
                    eventType, eventId, retryException.getMessage(), retryException);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /** 读取自定义重试次数 Header，异常值按已耗尽处理，避免恶意消息无限重试。 */
    private int readRetryCount(Message message) {
        Object value = message.getMessageProperties().getHeader(RETRY_COUNT_HEADER);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            log.warn("消息重试次数 Header 非法，按重试耗尽处理：value={}", value);
            return RabbitMQConfig.MAX_RETRY_COUNT;
        }
    }

    /** 指数退避：第 1/2/3 次重试分别等待 5/10/20 秒。 */
    private long calculateRetryDelay(int retryCount) {
        return RabbitMQConfig.BASE_RETRY_DELAY_MILLIS * (1L << (retryCount - 1));
    }

    /**
     * 根据 eventType 把消息体反序列化为具体事件对象。
     *
     * <p><b>【为什么用 switch 手动选择而不是让 Jackson 自动判断？】</b><br>
     * Jackson 的默认反序列化不会读取 eventType 字段来决定子类（需要额外配置
     * 多态注解 @JsonTypeInfo）。新手项目用 switch 最直观：
     * 6 种类型一目了然，未知类型走 default 抛异常，由调用方拒绝消息。</p>
     *
     * @param message   RabbitMQ 原始消息（body 是 JSON 字节数组）
     * @param eventType 事件类型（Header 中读取）
     * @return 反序列化后的具体事件对象
     * @throws IOException JSON 解析失败时抛出
     */
    private BaseNotificationEvent deserializeEvent(Message message, String eventType) throws IOException {
        // 根据 eventType 匹配具体事件类（常量引用，不会写错）
        Class<? extends BaseNotificationEvent> clazz = switch (eventType) {
            case ReservationCreatedEvent.EVENT_TYPE -> ReservationCreatedEvent.class;
            case ReservationCanceledEvent.EVENT_TYPE -> ReservationCanceledEvent.class;
            case WaitlistJoinedEvent.EVENT_TYPE -> WaitlistJoinedEvent.class;
            case WaitlistPromotedEvent.EVENT_TYPE -> WaitlistPromotedEvent.class;
            case ActivityReviewedEvent.EVENT_TYPE -> ActivityReviewedEvent.class;
            case ActivityUpcomingEvent.EVENT_TYPE -> ActivityUpcomingEvent.class;
            // 未知类型：抛出异常，由调用方拒绝消息（不重新入队，防毒消息死循环）
            default -> throw new IllegalArgumentException("未知事件类型：" + eventType);
        };
        return objectMapper.readValue(message.getBody(), clazz);
    }

    /**
     * 校验事件的公共字段和各事件特有字段。
     *
     * <p><b>为什么消费者还要校验一次？</b><br>
     * RabbitMQ 中的消息可能来自旧版本服务、测试工具或异常客户端，
     * 不能假设所有消息都经过当前生产者的校验。关键字段缺失时，
     * 数据库 INSERT 往往会失败；如果把这种错误当成临时故障重新入队，
     * 就会形成“毒消息死循环”。因此这里把它们提前识别为不可修复消息。</p>
     *
     * @param event          反序列化后的具体事件
     * @param headerEventType RabbitMQ Header 中的事件类型
     */
    private void validateEvent(BaseNotificationEvent event, String headerEventType) {
        if (event == null) {
            throw new IllegalArgumentException("消息事件对象不能为 null");
        }
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            throw new IllegalArgumentException("消息缺少 eventId");
        }
        if (event.getEventType() == null || event.getEventType().isBlank()) {
            throw new IllegalArgumentException("消息缺少 eventType");
        }
        if (!headerEventType.equals(event.getEventType())) {
            throw new IllegalArgumentException("消息 Header 与消息体中的 eventType 不一致");
        }
        if (event.getUserId() == null) {
            throw new IllegalArgumentException("消息缺少接收人 userId");
        }

        // 公共字段校验通过后，再校验每种事件自己的业务 ID。
        if (event instanceof ReservationCreatedEvent e
                && (e.getActivityId() == null || e.getReservationId() == null)) {
            throw new IllegalArgumentException("预约成功事件缺少 activityId 或 reservationId");
        }
        if (event instanceof ReservationCanceledEvent e
                && (e.getActivityId() == null || e.getReservationId() == null)) {
            throw new IllegalArgumentException("取消预约事件缺少 activityId 或 reservationId");
        }
        if (event instanceof WaitlistJoinedEvent e
                && (e.getActivityId() == null || e.getWaitlistId() == null
                || e.getQueuePosition() == null || e.getQueuePosition() <= 0)) {
            throw new IllegalArgumentException("加入候补事件缺少 activityId、waitlistId 或有效 queuePosition");
        }
        if (event instanceof WaitlistPromotedEvent e
                && (e.getActivityId() == null || e.getWaitlistId() == null)) {
            throw new IllegalArgumentException("候补补位事件缺少 activityId 或 waitlistId");
        }
        if (event instanceof ActivityReviewedEvent e
                && (e.getActivityId() == null || e.getPassed() == null)) {
            throw new IllegalArgumentException("活动审核事件缺少 activityId 或 passed");
        }
        if (event instanceof ActivityUpcomingEvent e
                && (e.getActivityId() == null || e.getActivityTitle() == null
                || e.getActivityTitle().isBlank() || e.getStartTime() == null)) {
            throw new IllegalArgumentException("活动即将开始事件缺少 activityId、activityTitle 或 startTime");
        }
    }
}
