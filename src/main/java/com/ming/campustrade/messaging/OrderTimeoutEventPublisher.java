package com.ming.campustrade.messaging;

import com.ming.campustrade.event.OrderTimeoutEvent;

/**
 * 订单超时事件发布接口。
 *
 * <p>订单 Service 只依赖这个业务接口，不直接依赖 RabbitTemplate，
 * 这样消息中间件细节集中在实现类，业务代码更容易理解和测试。</p>
 *
 * @author ming
 */
public interface OrderTimeoutEventPublisher {

    /** 把订单超时检查事件发送到 30 分钟 TTL 延迟队列。 */
    void publish(OrderTimeoutEvent event);
}
