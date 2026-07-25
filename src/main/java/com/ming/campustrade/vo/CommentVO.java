package com.ming.campustrade.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 商品留言视图对象（VO，View Object），用于向前端返回留言数据。
 *
 * <p>相比 {@code Comment} 实体，本 VO 额外携带了 {@code userNickname}、
 * {@code userAvatar} 等"留言者信息"以及 {@code replyToNickname}（被回复者昵称）。
 * 这些字段并不存在于留言表中，而是 Service 层根据用户 ID 关联查询用户表后填充进来的——
 * 这样前端展示留言时就能直接显示用户昵称和头像，无需再发一次请求。
 * 同时 VO 也屏蔽了 {@code deleted} 等内部字段，只暴露前端需要的内容。</p>
 *
 * @author ming
 */
@Data
public class CommentVO {

    /** 留言主键 ID。 */
    private Long id;

    /** 关联的商品 ID。 */
    private Long productId;

    /** 留言用户 ID。 */
    private Long userId;

    /** 留言者昵称（关联用户表填充，方便前端直接展示）。 */
    private String userNickname;

    /** 留言者头像 URL（关联用户表填充，方便前端直接展示）。 */
    private String userAvatar;

    /** 留言内容。 */
    private String content;

    /** 父留言 ID（null=顶级留言，非null=回复）。 */
    private Long parentId;

    /** 被回复的用户 ID（顶级留言时为 null）。 */
    private Long replyToUserId;

    /** 被回复者昵称（关联用户表填充，用于展示 "回复 @xxx"）。 */
    private String replyToNickname;

    /** 留言时间。 */
    private LocalDateTime createTime;
}
