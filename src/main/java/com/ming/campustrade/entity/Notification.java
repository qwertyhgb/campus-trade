package com.ming.campustrade.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 站内通知实体类，对应数据库中的 {@code notification} 表。
 *
 * <p><b>【本表的作用】</b><br>
 * 保存用户收到的所有站内通知（预约成功、取消预约、候补补位、审核结果等）。
 * 前端"消息中心"页面读取的就是这张表。</p>
 *
 * <p><b>【谁写入这张表？】</b><br>
 * 不是业务 Service 直接写，而是 RabbitMQ 消费者（NotificationMessageConsumer）
 * 消费事件消息后异步写入 —— 这正是消息队列解耦的体现：
 * 预约接口只负责发消息，写通知由消费者在后台完成。</p>
 *
 * <p><b>【为什么没有 deleted 字段？】</b><br>
 * 通知是用户的历史记录，不需要删除（表设计如此，不要添加不存在的字段）。
 * 已读/未读用 {@code isRead} 字段表达。</p>
 */
@Data
@TableName("notification")
public class Notification {

    /** 主键 ID，由数据库自增生成。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 接收通知的用户 ID（通知写给谁看）。 */
    private Long userId;

    /**
     * 通知类型，取值对应数据库注释：
     * 1预约成功 2预约取消 3加入候补 4候补补位成功 5审核通过 6审核拒绝 7活动即将开始。
     */
    private Integer type;

    /** 通知标题（如"预约成功"）。 */
    private String title;

    /** 通知内容（如"您已成功预约活动，活动ID：100"）。 */
    private String content;

    /**
     * 关联的业务 ID（活动 ID 或预约 ID），前端收到通知后
     * 可以用它跳转到对应的活动详情页/预约详情页。
     */
    private Long relatedId;

    /** 是否已读：0未读 1已读（默认 0）。 */
    private Integer isRead;

    /** 通知创建时间（数据库默认 CURRENT_TIMESTAMP）。 */
    private LocalDateTime createTime;
}