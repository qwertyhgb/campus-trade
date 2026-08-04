package com.ming.campustrade.config;

import lombok.extern.slf4j.Slf4j;

import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * RabbitMQ 消息转换与发送确认配置 —— 定制 RabbitTemplate 的行为。
 *
 * <p><b>【本配置类的职责】</b></p>
 * <ul>
 *   <li>注册 Jackson JSON 消息转换器：Java 事件对象 ↔ JSON</li>
 *   <li>开启发送确认回调（ConfirmCallback）：消息是否到达 Exchange</li>
 *   <li>开启消息退回回调（ReturnsCallback）：消息是否被路由到队列</li>
 * </ul>
 *
 * <p><b>【为什么使用 RabbitTemplateCustomizer？】</b><br>
 * Spring Boot 会自动创建 RabbitTemplate。这里不重新声明 RabbitTemplate Bean，
 * 而是提供一个 RabbitTemplateCustomizer，让 Spring Boot 在创建完 RabbitTemplate
 * 后自动调用定制逻辑。这样既能复用项目已有的连接配置，也能避免
 * "定制 Bean 需要注入自己"造成的循环依赖。</p>
 *
 * <p><b>【消息转换器的版本选择（重要）】</b><br>
 * Spring Boot 4.x 默认使用 Jackson 3（包名 {@code tools.jackson}）。
 * 因此这里使用 Spring AMQP 4.x 的 {@link JacksonJsonMessageConverter}
 * （Jackson 3 版），而不是已废弃的 {@code Jackson2JsonMessageConverter}
 * （Jackson 2 版，Spring AMQP 4.0 起标记废弃，将被移除）。
 * 注意：项目里的 {@code Jackson2CompatibilityConfig} 提供的是 Jackson 2 的
 * ObjectMapper（给旧代码用），本类注入的是 <b>Spring Boot 自动配置的
 * Jackson 3 ObjectMapper</b>，两者包名不同、互不冲突。</p>
 * <p><b>【消息转换器的作用】</b></p>
 * <ul>
 *   <li>发送时：Java 对象（如 ReservationCreatedEvent）→ JSON 字符串 → 写入 RabbitMQ</li>
 *   <li>消费时：JSON 字符串 → Java 对象（反序列化）</li>
 *   <li>额外好处：RabbitMQ 管理界面可以直接看到 JSON 格式的消息内容，方便排查</li>
 * </ul>
 *
 * <p><b>【发送确认（ConfirmCallback）的作用】</b><br>
 * 确认消息是否成功到达 Exchange。如果 Exchange 不存在或发送失败，回调会收到 nack。
 * 注意：<b>确认只能说明消息到达了 Exchange，不代表被路由到了队列</b>。
 * 路由失败由 ReturnsCallback 检测。</p>
 *
 * <p><b>【消息退回（ReturnsCallback）的作用】</b><br>
 * 当 Exchange 存在但<b>没有绑定能匹配 routingKey 的队列</b>时，
 * 消息会被 RabbitMQ 退回给生产者。需要同时开启 mandatory 模式
 * 才能收到退回通知。</p>
 *
 * @author ming
 */
@Slf4j
@Configuration
public class RabbitMessageConverterConfig {

    /**
     * 定制 Spring Boot 自动创建的 RabbitTemplate。
     *
     * <p><b>【定制的内容】</b></p>
     * <ol>
     *   <li>设置 JacksonJsonMessageConverter（Jackson 3 版），使用 Spring Boot 自动配置的
     *       Jackson 3 ObjectMapper（已注册 JavaTimeModule 支持 LocalDateTime）</li>
     *   <li>开启 mandatory 模式：消息无法路由到队列时退回生产者</li>
     *   <li>注册 ConfirmCallback：消息到达 Exchange 的结果回调</li>
     *   <li>注册 ReturnsCallback：消息无法路由到队列时的退回回调</li>
     * </ol>
     *
     * <p><b>【为什么不能直接声明一个 RabbitTemplate Bean？】</b><br>
     * 如果当前方法既返回 RabbitTemplate，又把 RabbitTemplate 作为参数，
     * Spring 创建这个 Bean 时就必须先获取它自己，最终形成循环依赖。
     * RabbitTemplateCustomizer 专门用于解决这种"自动配置 Bean 的后置定制"问题。</p>
     *
     * @param objectMapper Spring Boot 自动配置的 Jackson 3 ObjectMapper（实际类型是 JsonMapper）
     * @return RabbitTemplate 定制器
     */
    @Bean
    public RabbitTemplateCustomizer rabbitTemplateCustomizer(ObjectMapper objectMapper) {
        return rabbitTemplate -> {
        // ===== 1. 设置 Jackson JSON 消息转换器 =====
        // 使用 Spring Boot 自动配置的 Jackson 3 ObjectMapper。
        // 自动配置创建的实例实际是 JsonMapper 子类，所以可以直接转型。
        // 如果不设置消息转换器，RabbitTemplate 默认使用 SimpleMessageConverter，
        // 只能处理 String、byte[] 等简单类型，无法把事件对象自动转为 JSON。
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter((JsonMapper) objectMapper);
        rabbitTemplate.setMessageConverter(converter);

        // ===== 2. 开启 mandatory 模式（消息退回的必要条件） =====
        // mandatory = true：消息无法路由到队列时，RabbitMQ 把消息退回给生产者
        // mandatory = false（默认）：消息无法路由时，RabbitMQ 直接丢弃消息
        // 本项目的 binding 是固定的，正常情况下不会出现路由失败，
        // 但开启后可以及时发现 routingKey 写错或绑定配置遗漏的问题。
        rabbitTemplate.setMandatory(true);

        // ===== 3. 注册发送确认回调（ConfirmCallback） =====
        // 触发时机：消息发送到 Exchange 后，RabbitMQ 返回确认结果
        //   ack=true  → 消息成功到达 Exchange
        //   ack=false → 消息未到达 Exchange（如 Exchange 名称错误、连接异常）
        // CorrelationData 包含了 eventId，可以在回调中定位是哪条消息出问题。
        // 注意：ack=true 只表示消息到了 Exchange，不表示被路由到了队列！
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (correlationData == null) {
                // 极少数情况下 correlationData 可能为 null（如 RabbitTemplate 内部异常）
                log.warn("RabbitMQ 发送确认回调收到 null correlationData，ack={}", ack);
                return;
            }
            String eventId = correlationData.getId();
            if (ack) {
                log.debug("RabbitMQ 消息发送成功：eventId={}", eventId);
            } else {
                log.error("RabbitMQ 消息发送失败：eventId={}, 原因：{}", eventId, cause);
            }
        });

        // ===== 4. 注册消息退回回调（ReturnsCallback） =====
        // 触发时机：mandatory=true 且消息无法被路由到任何队列（如 routingKey 写错）
        // 打印详细信息便于排查 binding 配置或 routingKey 问题
        rabbitTemplate.setReturnsCallback(returned -> {
            log.warn("RabbitMQ 消息被退回：exchange={}, routingKey={}, " +
                            "回复码={}, 回复原因={}, 消息体={}",
                    returned.getExchange(),
                    returned.getRoutingKey(),
                    returned.getReplyCode(),
                    returned.getReplyText(),
                    returned.getMessage());
        });

        };
    }
}
