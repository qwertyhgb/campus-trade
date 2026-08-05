package com.ming.campustrade.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 站内通知视图对象（VO，View Object），用于向前端返回通知数据。
 *
 * <p><b>与 Notification 实体的区别：</b><br>
 * 不返回 userId（用户只能看到自己的通知，返回 userId 没有意义且可能泄露信息），
 * 不返回数据库内部字段，只暴露前端展示需要的内容。</p>
 *
 * @author ming
 */
@Data
public class NotificationVO {

    /** 通知 ID。 */
    private Long id;

    /** 通知类型：1预约成功 2预约取消 3加入候补 4候补补位成功 5审核通过 6审核拒绝 7活动即将开始。 */
    private Integer type;

    /** 通知标题（如"预约成功"）。 */
    private String title;

    /** 通知内容（如"您已成功预约活动，活动ID：100"）。 */
    private String content;

    /** 关联的业务 ID（活动 ID 或预约 ID），前端可跳转至详情页。 */
    private Long relatedId;

    /** 是否已读：0未读 1已读。 */
    private Integer isRead;

    /** 通知创建时间。 */
    private LocalDateTime createTime;
}