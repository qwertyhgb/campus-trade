package com.ming.campustrade.service.impl;

import com.ming.campustrade.service.ExternalNotificationService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 外部通知模拟实现。
 *
 * <p><b>这里为什么不真的发邮件/短信？</b><br>
 * 真实邮件和短信需要第三方账号、模板、签名和费用，不适合在学习阶段直接接入。
 * 但消息队列的关键学习点是“消费者收到事件后，异步调用通知渠道”，
 * 所以这里用日志模拟第三方平台的发送结果。</p>
 *
 * <p>将来替换为真实实现时，可以在方法内部调用邮件 SDK 或短信 SDK；
 * 调用方不需要知道底层供应商是谁。</p>
 *
 * @author ming
 */
@Slf4j
@Service
public class ExternalNotificationServiceImpl implements ExternalNotificationService {

    @Override
    public void sendSimulatedEmail(Long userId, String subject, String content) {
        validateUserId(userId);
        log.info("[模拟邮件] 已发送：userId={}, subject={}, content={}", userId, subject, content);
    }

    @Override
    public void sendSimulatedSms(Long userId, String content) {
        validateUserId(userId);
        log.info("[模拟短信] 已发送：userId={}, content={}", userId, content);
    }

    /** 防止模拟层收到残缺消息后打印出误导性的“发送成功”。 */
    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("外部通知接收人 userId 不合法");
        }
    }
}
