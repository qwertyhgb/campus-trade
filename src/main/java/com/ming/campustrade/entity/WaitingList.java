package com.ming.campustrade.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 候补实体类，对应数据库中的 {@code waiting_list} 表。
 *
 * <p>当活动正式名额已满时，用户可以进入候补队列。候补记录同样不使用逻辑删除，
 * 而是通过状态和有效标记保留“排队、补位、取消、失效”的历史。</p>
 *
 * <p>{@code queuePosition} 越小表示排队越靠前。后续候补业务会在事务中取出队首，
 * 将其转换成正式预约，并处理剩余候补者的位置。</p>
 */
@Data
@TableName("waiting_list")
public class WaitingList {

    /** 主键 ID，由数据库自增生成。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 加入候补队列的用户 ID。 */
    private Long userId;

    /** 对应的活动 ID。 */
    private Long activityId;

    /** 排队位置，从 1 开始，数值越小越靠前。 */
    private Integer queuePosition;

    /** 候补状态，取值见 {@link com.ming.campustrade.common.constant.WaitlistStatus}。 */
    private Integer status;

    /**
     * 有效标记：1 表示当前仍在候补，null 表示已补位、取消或失效。
     * 与数据库唯一索引配合，保证同一用户同一活动只有一条有效候补。
     */
    private Integer activeMark;

    /** 补位、取消或失效的处理时间。 */
    private LocalDateTime processTime;

    /** 加入候补队列的时间。 */
    private LocalDateTime createTime;

    /** 最后更新时间。 */
    private LocalDateTime updateTime;
}
