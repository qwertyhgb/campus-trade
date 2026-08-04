package com.ming.campustrade.service;

/**
 * 外部通知服务接口 —— 当前阶段只模拟发送邮件和短信。
 *
 * <p><b>为什么先抽象成接口？</b><br>
 * 业务代码只依赖“发送邮件/短信”这个能力，不直接依赖具体的日志实现。
 * 以后接入真实邮件服务商、短信服务商时，只需要替换实现类，
 * 不需要修改 RabbitMQ 消费者和通知业务。</p>
 *
 * <p>本项目的模拟实现不会真正连接第三方平台，只会打印清晰的日志，
 * 用来学习“消息消费后还可以分发到其他通知渠道”的完整链路。</p>
 *
 * @author ming
 */
public interface ExternalNotificationService {

    /** 模拟发送邮件。 */
    void sendSimulatedEmail(Long userId, String subject, String content);

    /** 模拟发送短信。 */
    void sendSimulatedSms(Long userId, String content);
}
