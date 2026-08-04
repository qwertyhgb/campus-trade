package com.ming.campustrade.event;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Data;

/**
 * 站内通知事件基类 —— 所有通过 RabbitMQ 发送的通知事件的公共父类。
 *
 * <p><b>【事件驱动架构：为什么需要事件对象？】</b><br>
 * 如果不使用事件，Service 要发通知就得直接去查数据库 + 写 notification 表，
 * 这会让预约/取消等核心方法的响应时间变长。事件驱动架构的做法是：
 * Service 只做一件事 —— 创建事件对象、发送到 RabbitMQ、立刻返回。
 * 消费者（另一个程序）从队列里取出事件，异步处理通知的写入。
 * 核心业务不因为发通知而被拖慢。</p>
 *
 * <p><b>【一次完整的事件投递流程（文字描述图）】</b></p>
 * <pre>
 * ┌──────────────────────┐
 * │    业务 Service       │
 * │  (reserve / cancel   │
 * │   joinWaitlist /     │
 * │   reviewActivity)    │
 * └──────────┬───────────┘
 *            │ ① 创建事件对象（eventId=UUID, eventType, userId, ...）
 *            │ ② rabbitTemplate.convertAndSend(exchange, routingKey, event)
 *            ▼
 * ┌──────────────────────┐     路由键完全匹配      ┌──────────────────────┐
 * │  RabbitMQ 交换机      │ ──────────────────────→ │  RabbitMQ 队列       │
 * │ (DirectExchange)     │    binding 绑定规则     │ (notification.queue) │
 * │ notification.exchange │                       │                      │
 * └──────────────────────┘                       └──────────┬───────────┘
 *                                                           │
 *                                                     ③ 消费者监听队列
 *                                                           ▼
 *               ┌──────────────────────────────────────────────┐
 *               │              消费者 (@RabbitListener)          │
 *               │                                                │
 *               │  ④ 查 message_consume_record 表：              │
 *               │     INSERT eventId → 唯一索引冲突？            │
 *               │     ├─ 冲突 → 已消费过，跳过（幂等）            │
 *               │     └─ 成功 → 继续处理                        │
 *               │                                                │
 *               │  ⑤ INSERT notification 表（写站内通知）         │
 *               └──────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>【事件投递的同时，核心业务已经返回了】</b><br>
 * 注意：上面整个流程（①→②→③→④→⑤）发生在<b>另一个线程</b>里。<br>
 * 用户点击"预约"按钮：Service 在 ① 创建事件、② 发送到 RabbitMQ，<b>方法就返回了</b>，
 * 用户立刻看到"预约成功"。③→④→⑤ 由消费者在后台异步完成，用户无需等待。</p>
 *
 * <p><b>【为什么不能直接发字符串？】</b><br>
 * 假设生产者只发 JSON 字符串 {@code {"msg":"预约成功"}}，消费者收到后无法可靠判断：
 * 这是什么事件（预约成功还是取消？）、属于哪个用户、关联哪个活动、
 * 是不是重复消息？事件对象把一条消息固化成"结构化单据"：</p>
 * <ul>
 *   <li>{@code eventId}：UUID，全局唯一 —— 消费幂等的核心（见下文）</li>
 *   <li>{@code eventType}：事件类型，如 RESERVATION_CREATED —— 消费者区分事件类别</li>
 *   <li>{@code occurredAt}：事件发生时间 —— 与消费时间无关，记录业务真实发生时刻</li>
 *   <li>{@code userId}：接收通知的用户 ID —— 通知写给谁看</li>
 *   <li>子类特有字段：{@code activityId}、{@code reservationId} 等 —— 各业务的上下文</li>
 * </ul>
 *
 * <p><b>【eventId 为什么必须由生产者生成并全局唯一？（面试重点）】</b><br>
 * RabbitMQ 是 <b>at-least-once（至少一次）</b>投递模型：保证消息一定被投递，
 * 但可能投递多次（网络抖动导致消费者应答丢失、消费超时重投、消费者重启等）。
 * 消费者用 eventId 查 {@code message_consume_record} 表判断是否已处理过：
 * 已处理 → 跳过；未处理 → 插入记录 + 处理业务。数据库的
 * {@code uk_event_id} 唯一索引保证同一个 eventId 只能插入一次，这就是
 * <b>消费幂等</b>的核心机制。</p>
 *
 * <p><b>【事件类继承结构（文字描述图）】</b></p>
 * <pre>
 *               BaseNotificationEvent (抽象类，本类)
 *               ├─ eventId: String (UUID)
 *               ├─ eventType: String
 *               ├─ occurredAt: LocalDateTime
 *               └─ userId: Long
 *                        │
 *          ┌─────────────┼─────────────┬──────────────┬──────────────┐
 *          │             │             │              │              │
 *          ▼             ▼             ▼              ▼              ▼
 *  Reservation  Reservation   Waitlist     Waitlist      Activity
 *  CreatedEvent  CanceledEvent  JoinedEvent  PromotedEvent  ReviewedEvent
 *  (预约成功)    (取消预约)     (加入候补)    (候补补位)     (活动审核)
 *  ├─activityId  ├─activityId  ├─activityId  ├─activityId   ├─activityId
 *  └─reservation │             └─waitlistId  └─waitlistId   ├─passed
 *    Id          └─reservation                                └─rejectReason
 *                   Id
 * </pre>
 *
 * <p><b>【事件 ↔ 路由键 ↔ 通知类型 映射表】</b></p>
 * <pre>
 * ┌────────────────────────┬──────────────────────────┬──────────────┐
 * │ 事件类                  │ 路由键 (Routing Key)      │ 通知类型     │
 * ├────────────────────────┼──────────────────────────┼──────────────┤
 * │ ReservationCreatedEvent│ reservation.created      │ 1 预约成功   │
 * │ ReservationCanceledEvent│ reservation.canceled     │ 2 预约取消   │
 * │ WaitlistJoinedEvent     │ waitlist.joined          │ 3 加入候补   │
 * │ WaitlistPromotedEvent   │ waitlist.promoted        │ 4 候补补位   │
 * │ ActivityReviewedEvent   │ activity.reviewed        │ 5/6 审核结果 │
 * └────────────────────────┴──────────────────────────┴──────────────┘
 * </pre>
 *
 * <p><b>【为什么是抽象类？】</b><br>
 * 事件对象按业务细分（预约成功/取消预约/加入候补/候补补位/活动审核），
 * 各自携带不同的业务字段（如 ReservationCreatedEvent 有 reservationId，
 * ActivityReviewedEvent 有 passed/rejectReason）。基类放公共字段。
 * 加 {@code abstract} 防止有人直接 new 一条"说不清类型"的事件发出去。</p>
 *
 * <p><b>【为什么用 @Data + 无参构造？】</b><br>
 * 事件对象要经过 Jackson 序列化成 JSON 存入 RabbitMQ 队列，
 * 消费者取出来后再反序列化回 Java 对象。Jackson 反序列化需要
 * <b>无参构造 + setter</b>，@Data 自动生成 getter/setter/equals/hashCode，
 * 满足全部需求。如果用了 {@code @AllArgsConstructor} 反而会覆盖无参构造，
 * 导致反序列化失败。</p>
 *
 * @author ming
 */
@Data
public abstract class BaseNotificationEvent {

    /**
     * 事件唯一 ID（UUID 字符串）。
     *
     * <p><b>【UUID 是什么】</b><br>
     * UUID（Universally Unique Identifier）是一个 128 位的全局唯一标识符，
     * 格式如 {@code 550e8400-e29b-41d4-a716-446655440000}。它不依赖数据库自增，
     * 在 Java 代码里直接生成，理论上不会重复（重复概率极低，低到可以忽略不计）。</p>
     *
     * <p><b>【为什么用 UUID 做 eventId？】</b><br>
     * 事件 ID 必须全局唯一，因为消费者用它做消费幂等：先往
     * {@code message_consume_record} 表 INSERT，唯一索引冲突说明已消费过。
     * 如果用数据库自增 ID，发送前就得先插数据库拿 ID —— 那还叫异步吗？
     * UUID 在内存中生成，不依赖任何外部系统，最适合做事件 ID。</p>
     */
    private String eventId;

    /**
     * 事件类型（大写蛇形，如 RESERVATION_CREATED）。
     *
     * <p><b>【路由键 vs 事件类型：两个概念的区别】</b><br>
     * 路由键（Routing Key，如 {@code reservation.created}）是 RabbitMQ 层面的概念，
     * 决定消息投递到哪个队列。事件类型（Event Type，如 {@code RESERVATION_CREATED}）
     * 是业务层面的概念，让消费者识别这是哪类事件。两者分工不同：
     * 路由键解决"消息去哪"，事件类型解决"消息是什么"。</p>
     *
     * <p>具体值在各个子类的 {@code EVENT_TYPE} 常量中定义。</p>
     */
    private String eventType;

    /** 事件发生时间（生产者记录，与消费者的消费时间无关）。 */
    private LocalDateTime occurredAt;

    /**
     * 接收通知的用户 ID。
     *
     * <p>通知写给谁看，就填谁的 ID。不同事件接收者不同：</p>
     * <ul>
     *   <li>预约成功 → 预约用户本人</li>
     *   <li>取消预约 → 活动组织者（组织者需要知道有人取消了）</li>
     *   <li>加入候补 → 候补用户本人</li>
     *   <li>候补补位 → 被补位的用户本人</li>
     *   <li>活动审核 → 活动组织者</li>
     * </ul>
     */
    private Long userId;

    /**
     * 初始化事件的公共字段。
     *
     * <p><b>【为什么设计成 initEvent 而不是在构造方法里做？】</b><br>
     * 子类继承时，如果父类构造方法里有 UUID 生成逻辑，子类构造方法必须先调 super()。
     * 用 initEvent 方法更灵活：子类先在默认构造方法里创建对象，再调 initEvent
     * 设置公共字段，最后设置自己的特殊字段。静态工厂方法 {@code create()} 内部
     * 就是这样三步走的。</p>
     *
     * <p>生成规则：eventId 用 UUID（全局唯一）、occurredAt 用当前系统时间。</p>
     *
     * @param eventType 事件类型（子类传入自己的 EVENT_TYPE 常量）
     * @param userId    接收通知的用户 ID
     */
    protected void initEvent(String eventType, Long userId) {
        this.eventId = UUID.randomUUID().toString();
        this.eventType = eventType;
        this.occurredAt = LocalDateTime.now();
        this.userId = userId;
    }
}