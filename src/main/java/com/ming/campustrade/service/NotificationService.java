package com.ming.campustrade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ming.campustrade.event.BaseNotificationEvent;
import com.ming.campustrade.vo.NotificationVO;

/**
 * 站内通知业务逻辑接口（Service 层）。
 *
 * <p>包含两类职责：</p>
 * <ul>
 *   <li>消费者写入：{@link #processIfNew(BaseNotificationEvent, String)} —— 幂等消费</li>
 *   <li>用户查询：{@link #getMyNotifications} / {@link #getUnreadCount} / 已读管理</li>
 * </ul>
 *
 * @author ming
 */
public interface NotificationService {

    /**
     * 处理一条通知事件（幂等消费的核心方法）。
     *
     * @param event     通知事件（必须已初始化：eventId、eventType、userId 非空）
     * @param queueName 队列名称
     * @return true=第一次消费，已生成通知；false=重复消息，已跳过
     */
    boolean processIfNew(BaseNotificationEvent event, String queueName);

    /**
     * 分页查询当前用户的通知列表。
     *
     * <p><b>关键安全约束：</b>查询条件必须带 {@code user_id = 当前用户 ID}，
     * 不能只根据通知 ID 查询，否则用户可能看到别人的通知。</p>
     *
     * @param pageNo    页码（从 1 开始）
     * @param pageSize  每页条数
     * @param unreadOnly true=只看未读，false=全部
     * @return 通知 VO 分页对象
     */
    IPage<NotificationVO> getMyNotifications(int pageNo, int pageSize, boolean unreadOnly);

    /**
     * 查询当前用户未读通知数量。
     *
     * @return 未读通知数量
     */
    long getUnreadCount();

    /**
     * 标记单条通知为已读。
     *
     * <p>使用条件更新（id + user_id），防止用户标记别人的通知为已读。</p>
     *
     * @param notificationId 通知 ID
     */
    void markAsRead(Long notificationId);

    /**
     * 标记当前用户所有通知为已读。
     */
    void markAllAsRead();
}