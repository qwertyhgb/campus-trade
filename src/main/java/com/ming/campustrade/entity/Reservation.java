package com.ming.campustrade.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 预约实体类，对应数据库中的 {@code reservation} 表。
 *
 * <p>预约表和商品/活动表不同：它不使用 {@code deleted} 逻辑删除字段。
 * 用户取消预约后，记录仍然保留，通过 {@code status} 和 {@code activeMark}
 * 表示“当前是否有效”。这样既能防止重复有效预约，也能保留完整历史。</p>
 *
 * <p>特别注意：{@code activeMark} 必须使用 {@link Integer}，不能使用基本类型
 * {@code int}。因为有效记录的值是 1，无效记录的值是数据库 NULL，只有包装类型
 * 才能在 Java 中表达 null。</p>
 */
@Data
@TableName("reservation")
public class Reservation {

    /** 主键 ID，由数据库自增生成。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 发起预约的用户 ID。 */
    private Long userId;

    /** 被预约的活动 ID。 */
    private Long activityId;

    /** 预约状态，取值见 {@link com.ming.campustrade.common.constant.ReservationStatus}。 */
    private Integer status;

    /**
     * 有效标记：1 表示当前有效，null 表示历史无效。
     * 与数据库唯一索引配合，保证同一用户同一活动只有一条有效预约。
     */
    private Integer activeMark;

    /** 用户取消预约或预约失效的时间。 */
    private LocalDateTime cancelTime;

    /** 预约创建时间。 */
    private LocalDateTime createTime;

    /** 最后更新时间。 */
    private LocalDateTime updateTime;
}
