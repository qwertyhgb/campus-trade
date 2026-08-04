package com.ming.campustrade.config;

import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 基础资源配置 —— 集中声明本项目要用到的消息基础设施。
 *
 * <p><b>【先搞懂 RabbitMQ 是什么】</b><br>
 * RabbitMQ 是一个"消息队列"中间件，核心作用是<b>异步解耦</b>。
 * 拿本项目举例：用户预约成功后要发站内通知。如果不用消息队列，
 * Service 里要"同步"地往 notification 表插记录 + 各种处理，会拖慢预约接口的响应。
 * 用了消息队列后，预约接口只做一件事——把"预约成功"事件丢给 RabbitMQ，立刻返回。
 * 至于通知怎么发、发给谁，由后台的消费者慢慢处理，两边互不阻塞。</p>
 *
 * <p><b>【三个核心概念（快递流程类比）】</b></p>
 * <ul>
 *     <li><b>交换机（Exchange）</b>：像快递分拣中心，负责根据"路由键"决定消息送去哪个队列；</li>
 *     <li><b>队列（Queue）</b>：像快递柜，暂存等待消费者处理的消息；</li>
 *     <li><b>绑定（Binding）</b>：像分拣规则表，规定"路由键 X 的消息 → 送进队列 Y"。</li>
 * </ul>
 *
 * <p><b>【一次完整消息投递流程】</b><br>
 * 生产者（Producer，通常是我们的 Service）发送消息到交换机，同时带上一个<b>路由键</b>
 * （Routing Key，如 "reservation.created"）。交换机查看绑定规则：
 * 路由键匹配的绑定 → 把消息投进对应的队列。消费者（Consumer，监听队列的程序）
 * 从队列里取消息处理。RabbitMQ 默认是<b>推模式</b>：队列有消息就推给消费者。</p>
 *
 * <p><b>【本项目当前的设计】</b><br>
 * 六类消息（预约成功/取消预约/加入候补/候补补位/活动审核/活动即将开始）都属于"站内通知"，
 * 处理方式完全相同（写入 notification 表），所以先<b>共用一个主队列</b>。
 * 主队列另外配置了重试队列和死信队列：临时故障延迟重试，始终无法处理的消息进入死信队列，
 * 方便后续人工排查，而不是无限占用主队列。</p>
 *
 * <p><b>【为什么选择 DirectExchange 而不是其他类型？】</b><br>
 * RabbitMQ 有多种交换机：Direct（路由键完全匹配）、Topic（通配符匹配）、
 * Fanout（广播，忽略路由键）。本项目路由键都是固定值，完全匹配即可，
 * Direct 规则最简单、最不容易误投递，所以选它。</p>
 *
 * @author ming
 */
@Slf4j
@Configuration
public class RabbitMQConfig {

    /**
     * 站内通知交换机名称。
     *
     * <p>命名规范：用点号分层（"notification.exchange"），
     * 类似包名，一看就知道这个交换机属于"通知"模块。</p>
     */
    public static final String EXCHANGE_NAME = "notification.exchange";

    /** 站内通知队列名称（所有通知消息暂存在这里，等待消费者处理）。 */
    public static final String QUEUE_NAME = "notification.queue";

    /** 临时失败消息进入的重试交换机。 */
    public static final String RETRY_EXCHANGE_NAME = "notification.retry.exchange";

    /** 临时失败消息进入的延迟重试队列。 */
    public static final String RETRY_QUEUE_NAME = "notification.retry.queue";

    /** 重试消息使用的固定路由键，TTL 到期后会重新回到主队列。 */
    public static final String RK_RETRY = "notification.retry";

    /** 无法处理的消息进入的死信交换机（Dead Letter Exchange，简称 DLX）。 */
    public static final String DEAD_LETTER_EXCHANGE_NAME = "notification.dlx";

    /** 死信队列名称：保存格式错误或重试次数耗尽的消息。 */
    public static final String DEAD_LETTER_QUEUE_NAME = "notification.dead.queue";

    /** 死信消息使用的路由键。 */
    public static final String RK_DEAD_LETTER = "notification.dead";

    /** 单条消息最多自动重试次数，超过后转入死信队列。 */
    public static final int MAX_RETRY_COUNT = 3;

    /** 第一次重试等待时间；后续重试采用 5 秒、10 秒、20 秒的指数退避。 */
    public static final long BASE_RETRY_DELAY_MILLIS = 5_000L;

    // ==================== 订单超时取消专用资源 ====================

    /** 订单超时消息到达消费者前的最终交换机。 */
    public static final String ORDER_TIMEOUT_EXCHANGE_NAME = "order.timeout.exchange";

    /** 订单超时消费者实际监听的队列。 */
    public static final String ORDER_TIMEOUT_QUEUE_NAME = "order.timeout.queue";

    /** 订单创建后先进入的延迟交换机。 */
    public static final String ORDER_TIMEOUT_DELAY_EXCHANGE_NAME = "order.timeout.delay.exchange";

    /** 保存等待中的订单超时消息，TTL 到期后转发到 ORDER_TIMEOUT_EXCHANGE_NAME。 */
    public static final String ORDER_TIMEOUT_DELAY_QUEUE_NAME = "order.timeout.delay.queue";

    /** 订单超时临时失败的重试交换机。 */
    public static final String ORDER_TIMEOUT_RETRY_EXCHANGE_NAME = "order.timeout.retry.exchange";

    /** 订单超时临时失败的重试队列。 */
    public static final String ORDER_TIMEOUT_RETRY_QUEUE_NAME = "order.timeout.retry.queue";

    /** 订单超时无法处理时使用的死信交换机。 */
    public static final String ORDER_TIMEOUT_DEAD_LETTER_EXCHANGE_NAME = "order.timeout.dlx";

    /** 订单超时无法处理时保存消息的死信队列。 */
    public static final String ORDER_TIMEOUT_DEAD_LETTER_QUEUE_NAME = "order.timeout.dead.queue";

    /** 订单超时主路由键：延迟到期后进入消费者队列。 */
    public static final String RK_ORDER_TIMEOUT = "order.timeout";

    /** 订单超时重试路由键：临时失败消息进入重试队列。 */
    public static final String RK_ORDER_TIMEOUT_RETRY = "order.timeout.retry";

    /** 订单超时死信路由键。 */
    public static final String RK_ORDER_TIMEOUT_DEAD = "order.timeout.dead";

    /** 当前订单超时规则：下单后 30 分钟触发超时检查。 */
    public static final long ORDER_TIMEOUT_DELAY_MILLIS = 30 * 60 * 1_000L;

    /** 订单超时临时失败的第一次重试等待时间。 */
    public static final long ORDER_TIMEOUT_BASE_RETRY_DELAY_MILLIS = 5_000L;

    /** 订单超时消息最多自动重试次数。 */
    public static final int ORDER_TIMEOUT_MAX_RETRY_COUNT = 3;

    /** 预约成功事件：通知相关用户或组织者。 */
    public static final String RK_RESERVATION_CREATED = "reservation.created";

    /** 取消预约事件：通知活动组织者。 */
    public static final String RK_RESERVATION_CANCELED = "reservation.canceled";

    /** 加入候补事件：记录候补加入通知。 */
    public static final String RK_WAITLIST_JOINED = "waitlist.joined";

    /** 候补补位成功事件：通知用户已经获得正式名额。 */
    public static final String RK_WAITLIST_PROMOTED = "waitlist.promoted";

    /** 活动审核完成事件：通知活动组织者审核结果。 */
    public static final String RK_ACTIVITY_REVIEWED = "activity.reviewed";

    /** 活动即将开始事件：通知已预约的用户活动即将开始。 */
    public static final String RK_ACTIVITY_UPCOMING = "activity.upcoming";

    /**
     * 应用启动时输出一次基础资源配置摘要。
     *
     * <p><b>【@PostConstruct 是什么】</b><br>
     * 它是 Java 标准注解（jakarta.annotation），标注的方法会在<b>对象构造完成、
     * 依赖注入完成后</b>自动执行一次。Spring 容器创建本配置类的实例时，
     * 先执行构造方法 → 注入依赖 → 然后调用这个 @PostConstruct 方法。</p>
     *
     * <p><b>【为什么要打印这条日志】</b><br>
     * 新手排查问题时最常问"我的配置到底加载了没有"。
     * 这条日志（不含密码等敏感信息）就是加载证据：
     * 看到它说明 Spring 已扫描到本配置类，下面的 @Bean 方法才会被调用。</p>
     */
    @PostConstruct
    public void logConfigurationLoaded() {
        log.info("RabbitMQ 资源配置已加载：exchange={}, queue={}，等待 RabbitAdmin 声明资源",
                EXCHANGE_NAME, QUEUE_NAME);
    }

    /**
     * 声明持久化直连交换机。
     *
     * <p><b>【DirectExchange 构造方法的三个参数】</b></p>
     * <ol>
     *     <li>{@code EXCHANGE_NAME}：交换机名称（字符串常量，发送/接收都要用它）</li>
     *     <li>{@code true}：durable，持久化 —— RabbitMQ 服务重启后，交换机定义仍然存在；
     *         如果设 false，服务一重启交换机就消失，后续发送消息会报"交换机不存在"</li>
     *     <li>{@code false}：autoDelete，不自动删除 —— 最后一个绑定被移除时不会删掉交换机</li>
     * </ol>
     *
     * <p><b>【注意区分两个"持久化"】</b><br>
     * 交换机持久化 ≠ 消息不丢失。消息是否持久化取决于发送时的 MessageProperties
     * （deliveryMode=2），这部分在后续发送消息的步骤配置。本类只管"基础设施"。</p>
     *
     * @return 站内通知直连交换机
     */
    @Bean
    public DirectExchange notificationExchange() {
        return new DirectExchange(EXCHANGE_NAME, true, false);
    }

    /**
     * 声明重试交换机。
     *
     * <p>临时性故障不能马上把消息重新放回主队列，否则消费者会立刻再次收到同一条消息，
     * 形成"快速失败 → 立即重试"的忙等循环。单独的重试交换机配合 TTL 队列，
     * 可以让消息先等待一段时间再回到主队列。</p>
     */
    @Bean
    public DirectExchange notificationRetryExchange() {
        return new DirectExchange(RETRY_EXCHANGE_NAME, true, false);
    }

    /**
     * 声明死信交换机（DLX）。
     *
     * <p>当消费者对主队列消息执行 basicNack(..., requeue=false) 时，
     * RabbitMQ 会按照主队列的死信配置，把消息投递到这个交换机。</p>
     */
    @Bean
    public DirectExchange notificationDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE_NAME, true, false);
    }

    /** 订单超时最终交换机：TTL 到期的消息会被路由到消费者队列。 */
    @Bean
    public DirectExchange orderTimeoutExchange() {
        return new DirectExchange(ORDER_TIMEOUT_EXCHANGE_NAME, true, false);
    }

    /** 订单超时延迟交换机：创建订单后，生产者把消息先发送到这里。 */
    @Bean
    public DirectExchange orderTimeoutDelayExchange() {
        return new DirectExchange(ORDER_TIMEOUT_DELAY_EXCHANGE_NAME, true, false);
    }

    /** 订单超时重试交换机：消费者临时失败时把消息送入这里。 */
    @Bean
    public DirectExchange orderTimeoutRetryExchange() {
        return new DirectExchange(ORDER_TIMEOUT_RETRY_EXCHANGE_NAME, true, false);
    }

    /** 订单超时死信交换机：保存格式错误或重试次数耗尽的消息。 */
    @Bean
    public DirectExchange orderTimeoutDeadLetterExchange() {
        return new DirectExchange(ORDER_TIMEOUT_DEAD_LETTER_EXCHANGE_NAME, true, false);
    }

    /**
     * 声明持久化通知队列。
     *
     * <p><b>【队列是消息真正"排队等待"的地方】</b><br>
     * 生产者把消息投进交换机，交换机按绑定规则把消息送进队列，
     * 队列暂时保存消息，直到消费者来取走处理。</p>
     *
     * <p><b>【QueueBuilder.durable 做了什么】</b><br>
     * 创建一个持久化队列：RabbitMQ 重启后队列定义和队列里的持久化消息都还在。
     * 如果队列是非持久化的，服务重启后队列直接消失（消息也随之丢失）。
     * 通知属于重要业务数据，必须持久化。</p>
     *
     * <p><b>【为什么不自动删除】</b><br>
     * 有些队列设置了 autoDelete：当最后一个消费者断开连接时队列被自动删除。
     * 通知队列的消息可能由定时任务间歇消费，中途可能长时间没有消费者在线，
     * 如果自动删除，队列和未处理的消息就没了，所以这里保持"不自动删除"。</p>
     *
     * @return 站内通知队列
     */
    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                // 主队列拒绝且不重新入队的消息，自动转到死信交换机。
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE_NAME)
                .withArgument("x-dead-letter-routing-key", RK_DEAD_LETTER)
                .build();
    }

    /**
     * 声明延迟重试队列。
     *
     * <p>RabbitMQ 原生队列没有"让某一条消息睡眠几秒"的普通 API，
     * 常见入门方案是：消息进入设置了 TTL 的队列，时间到期后由该队列的死信配置
     * 转发回主交换机。这里固定 TTL 为最长一次等待时间，具体等待时长由消息的 expiration 属性控制。</p>
     */
    @Bean
    public Queue notificationRetryQueue() {
        return QueueBuilder.durable(RETRY_QUEUE_NAME)
                .withArgument("x-message-ttl", 20_000)
                .withArgument("x-dead-letter-exchange", EXCHANGE_NAME)
                .withArgument("x-dead-letter-routing-key", RK_RETRY)
                .build();
    }

    /**
     * 声明死信队列。
     *
     * <p>死信队列不配置消费者，目的是让开发者可以在 RabbitMQ 管理界面看到问题消息，
     * 后续可以增加专门的死信处理器或人工补偿工具。</p>
     */
    @Bean
    public Queue notificationDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE_NAME).build();
    }

    /**
     * 订单超时消费者队列。
     *
     * <p>消费者对消息执行 {@code basicNack(requeue=false)} 时，RabbitMQ 会根据这里的
     * DLX 参数把消息送入订单超时死信队列，而不是悄悄丢弃。</p>
     */
    @Bean
    public Queue orderTimeoutQueue() {
        return QueueBuilder.durable(ORDER_TIMEOUT_QUEUE_NAME)
                .withArgument("x-dead-letter-exchange", ORDER_TIMEOUT_DEAD_LETTER_EXCHANGE_NAME)
                .withArgument("x-dead-letter-routing-key", RK_ORDER_TIMEOUT_DEAD)
                .build();
    }

    /**
     * 订单超时延迟队列。
     *
     * <p>RabbitMQ 没有普通队列级别的“单条消息定时器”，所以采用 TTL + 死信转发：
     * 消息先在这里等待 30 分钟，TTL 到期后由 x-dead-letter-exchange 转发到最终交换机。</p>
     */
    @Bean
    public Queue orderTimeoutDelayQueue() {
        return QueueBuilder.durable(ORDER_TIMEOUT_DELAY_QUEUE_NAME)
                .withArgument("x-message-ttl", ORDER_TIMEOUT_DELAY_MILLIS)
                .withArgument("x-dead-letter-exchange", ORDER_TIMEOUT_EXCHANGE_NAME)
                .withArgument("x-dead-letter-routing-key", RK_ORDER_TIMEOUT)
                .build();
    }

    /**
     * 订单超时重试队列。
     *
     * <p>消费者临时失败时，消息进入这里等待 5/10/20 秒；TTL 到期后再回到订单超时主队列。
     * 20 秒是队列级上限，具体等待时间由消费者写入的消息 expiration 属性决定。</p>
     */
    @Bean
    public Queue orderTimeoutRetryQueue() {
        return QueueBuilder.durable(ORDER_TIMEOUT_RETRY_QUEUE_NAME)
                .withArgument("x-message-ttl", 20_000)
                .withArgument("x-dead-letter-exchange", ORDER_TIMEOUT_EXCHANGE_NAME)
                .withArgument("x-dead-letter-routing-key", RK_ORDER_TIMEOUT)
                .build();
    }

    /** 订单超时死信队列：保留无法自动处理的订单消息，便于人工排查。 */
    @Bean
    public Queue orderTimeoutDeadLetterQueue() {
        return QueueBuilder.durable(ORDER_TIMEOUT_DEAD_LETTER_QUEUE_NAME).build();
    }

    /**
     * 注册 RabbitAdmin，让 Spring 在应用启动时把本类中的声明同步到 RabbitMQ。
     *
     * <p><b>【RabbitAdmin 是"基础设施管理员"】</b><br>
     * 注意一个关键点：上面定义的 {@code DirectExchange}、{@code Queue}、{@code Binding}
     * 只是 <b>Spring 容器里的 Java 对象</b>，它们还<b>不存在于 RabbitMQ 服务器</b>！
     * 是谁把它们真正创建到 RabbitMQ 里？—— 就是 RabbitAdmin。
     * 它会扫描容器中的所有 Exchange/Queue/Binding Bean，连接 RabbitMQ 后
     * 自动执行"声明"操作（创建交换机、创建队列、建立绑定）。</p>
     *
     * <p><b>【为什么没有它可能出问题】</b><br>
     * 某些运行环境下如果没有 RabbitAdmin（或没触发初始化），代码发送消息时会报
     * "channel error; protocol method: #method&lt;channel.close&gt; reply-code=404
     * reply-text=NOT_FOUND - no exchange 'xxx'"—— 即交换机不存在。</p>
     *
     * <p><b>【@ConditionalOnMissingBean 是什么（新手必懂）】</b><br>
     * 条件注解："如果容器里<b>没有</b>指定类型的 Bean，才创建我这个 Bean；有就不创建"。
     * Spring Boot 的 RabbitAutoConfiguration 通常会自动配好一个 RabbitAdmin，
     * 这里加这个条件是为了：自动配置已存在 → 复用（避免重复注册两个管理员）；
     * 自动配置不存在 → 我们自己补一个。保证任何环境下容器里都恰好只有一个。</p>
     *
     * @param connectionFactory Spring Boot 根据 application-dev.yaml 创建的 RabbitMQ 连接工厂
     * @return RabbitMQ 基础资源管理员
     */
    @Bean
    @ConditionalOnMissingBean(AmqpAdmin.class)
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    /**
     * 启动时主动声明 RabbitMQ 资源。
     *
     * <p><b>【为什么需要主动初始化？】</b><br>
     * RabbitAdmin 默认"懒"：它监听连接建立事件，有连接建立时才执行资源声明。
     * 正常业务运行时，生产者（RabbitTemplate）或消费者（@RabbitListener）会主动建连，
     * RabbitAdmin 自然就会声明资源。即使当前已经有生产者和消费者，开发启动时主动调用
     * initialize() 仍然能让交换机、队列和绑定更早、更确定地完成声明，便于排查配置问题。</p>
     *
     * <p><b>【ApplicationRunner 是什么】</b><br>
     * Spring Boot 的接口：所有 ApplicationRunner 的 run() 方法会在
     * Spring 容器启动完成后、应用对外服务前统一执行。常用来做"启动后初始化"。
     * 这里用它在启动时主动调 {@link RabbitAdmin#initialize()}，
     * 强制立刻把资源声明到 RabbitMQ，开发时打开管理界面就能看到。</p>
     *
     * <p><b>【initialize() 是幂等的】</b><br>
     * 资源已经存在时，RabbitMQ 的声明操作不会重复创建或报错，重复执行安全。</p>
     *
     * <p><b>【为什么 RabbitMQ 不可用就要启动失败（设计取舍）】</b><br>
     * initialize() 连不上 RabbitMQ 会抛异常 → 应用启动失败。
     * 这是<b>有意为之</b>：后续通知功能强依赖消息队列，
     * 早点暴露"RabbitMQ 没起"的问题，比应用假装正常启动、之后悄悄丢消息好得多。</p>
     *
     * @param amqpAdmin Spring Boot 自动配置或本类提供的 RabbitAdmin
     * @return 启动初始化任务
     */
    @Bean
    public ApplicationRunner rabbitMqResourceInitializer(AmqpAdmin amqpAdmin) {
        return args -> {
            if (!(amqpAdmin instanceof RabbitAdmin rabbitAdmin)) {
                throw new IllegalStateException("RabbitMQ 管理器不是 RabbitAdmin，无法初始化消息资源");
            }

            rabbitAdmin.initialize();
            log.info("RabbitMQ 资源初始化完成：通知 9 条绑定 + 订单超时 4 条绑定，主队列={}",
                    QUEUE_NAME);
        };
    }

    /**
     * 把"预约成功"路由键绑定到通知队列。
     *
     * <p><b>【BindingBuilder 链式调用怎么读】</b><br>
     * {@code BindingBuilder.bind(队列).to(交换机).with(路由键)} —— 按英文直译就是：
     * "把队列绑定到交换机上，路由键为 xxx"。生成一条规则：
     * 发送到 notification.exchange、路由键为 reservation.created 的消息
     * → 投进 notification.queue 队列。</p>
     *
     * <p><b>【为什么每个绑定都要单独声明一个 @Bean】</b><br>
     * Spring 容器中同类型多个 Bean 靠方法名区分，每个绑定一个 @Bean 方法
     * （方法名即 Bean 名），RabbitAdmin 才能扫到全部 9 条绑定。
     * 如果在一个方法里 new 多个 Binding 返回，只有一个是 Bean，其余不会被扫描到。</p>
     */
    @Bean
    public Binding reservationCreatedBinding(
            @Qualifier("notificationQueue") Queue notificationQueue,
            @Qualifier("notificationExchange") DirectExchange notificationExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(notificationExchange)
                .with(RK_RESERVATION_CREATED);
    }

    /**
     * 把"取消预约"路由键绑定到通知队列。
     */
    @Bean
    public Binding reservationCanceledBinding(
            @Qualifier("notificationQueue") Queue notificationQueue,
            @Qualifier("notificationExchange") DirectExchange notificationExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(notificationExchange)
                .with(RK_RESERVATION_CANCELED);
    }

    /**
     * 把"加入候补"路由键绑定到通知队列。
     */
    @Bean
    public Binding waitlistJoinedBinding(
            @Qualifier("notificationQueue") Queue notificationQueue,
            @Qualifier("notificationExchange") DirectExchange notificationExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(notificationExchange)
                .with(RK_WAITLIST_JOINED);
    }

    /**
     * 把"候补补位成功"路由键绑定到通知队列。
     */
    @Bean
    public Binding waitlistPromotedBinding(
            @Qualifier("notificationQueue") Queue notificationQueue,
            @Qualifier("notificationExchange") DirectExchange notificationExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(notificationExchange)
                .with(RK_WAITLIST_PROMOTED);
    }

    /**
     * 把"活动审核完成"路由键绑定到通知队列。
     */
    @Bean
    public Binding activityReviewedBinding(
            @Qualifier("notificationQueue") Queue notificationQueue,
            @Qualifier("notificationExchange") DirectExchange notificationExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(notificationExchange)
                .with(RK_ACTIVITY_REVIEWED);
    }

    /**
     * 把"活动即将开始"路由键绑定到通知队列。
     */
    @Bean
    public Binding activityUpcomingBinding(
            @Qualifier("notificationQueue") Queue notificationQueue,
            @Qualifier("notificationExchange") DirectExchange notificationExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(notificationExchange)
                .with(RK_ACTIVITY_UPCOMING);
    }

    /**
     * 把重试回来的消息路由到主通知队列。
     *
     * <p>重试队列 TTL 到期后，会把消息发送到主交换机并使用 RK_RETRY；
     * 这条绑定就是"重试交换机 → 主队列"的回程路线。</p>
     */
    @Bean
    public Binding retryToMainQueueBinding(
            @Qualifier("notificationQueue") Queue notificationQueue,
            @Qualifier("notificationExchange") DirectExchange notificationExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(notificationExchange)
                .with(RK_RETRY);
    }

    /** 重试交换机把失败消息送入带 TTL 的重试队列。 */
    @Bean
    public Binding retryQueueBinding(
            @Qualifier("notificationRetryQueue") Queue notificationRetryQueue,
            @Qualifier("notificationRetryExchange") DirectExchange notificationRetryExchange) {
        return BindingBuilder.bind(notificationRetryQueue)
                .to(notificationRetryExchange)
                .with(RK_RETRY);
    }

    /** 死信交换机把问题消息送入死信队列，供管理界面或后续人工补偿查看。 */
    @Bean
    public Binding deadLetterQueueBinding(
            @Qualifier("notificationDeadLetterQueue") Queue notificationDeadLetterQueue,
            @Qualifier("notificationDeadLetterExchange") DirectExchange notificationDeadLetterExchange) {
        return BindingBuilder.bind(notificationDeadLetterQueue)
                .to(notificationDeadLetterExchange)
                .with(RK_DEAD_LETTER);
    }

    /** 创建订单后，订单超时消息先进入 TTL 延迟队列。 */
    @Bean
    public Binding orderTimeoutDelayQueueBinding(
            @Qualifier("orderTimeoutDelayQueue") Queue orderTimeoutDelayQueue,
            @Qualifier("orderTimeoutDelayExchange") DirectExchange orderTimeoutDelayExchange) {
        return BindingBuilder.bind(orderTimeoutDelayQueue)
                .to(orderTimeoutDelayExchange)
                .with(RK_ORDER_TIMEOUT);
    }

    /** TTL 到期后，消息由最终交换机路由给订单超时消费者队列。 */
    @Bean
    public Binding orderTimeoutQueueBinding(
            @Qualifier("orderTimeoutQueue") Queue orderTimeoutQueue,
            @Qualifier("orderTimeoutExchange") DirectExchange orderTimeoutExchange) {
        return BindingBuilder.bind(orderTimeoutQueue)
                .to(orderTimeoutExchange)
                .with(RK_ORDER_TIMEOUT);
    }

    /** 订单超时临时失败消息进入重试队列。 */
    @Bean
    public Binding orderTimeoutRetryQueueBinding(
            @Qualifier("orderTimeoutRetryQueue") Queue orderTimeoutRetryQueue,
            @Qualifier("orderTimeoutRetryExchange") DirectExchange orderTimeoutRetryExchange) {
        return BindingBuilder.bind(orderTimeoutRetryQueue)
                .to(orderTimeoutRetryExchange)
                .with(RK_ORDER_TIMEOUT_RETRY);
    }

    /** 订单超时无法处理的消息进入订单专用死信队列。 */
    @Bean
    public Binding orderTimeoutDeadLetterQueueBinding(
            @Qualifier("orderTimeoutDeadLetterQueue") Queue orderTimeoutDeadLetterQueue,
            @Qualifier("orderTimeoutDeadLetterExchange") DirectExchange orderTimeoutDeadLetterExchange) {
        return BindingBuilder.bind(orderTimeoutDeadLetterQueue)
                .to(orderTimeoutDeadLetterExchange)
                .with(RK_ORDER_TIMEOUT_DEAD);
    }
}
