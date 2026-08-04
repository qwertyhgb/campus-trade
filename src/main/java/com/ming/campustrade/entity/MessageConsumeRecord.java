package com.ming.campustrade.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 消息消费记录实体类，对应数据库中的 {@code message_consume_record} 表。
 *
 * <p><b>【本表的作用 —— RabbitMQ 消费幂等的数据库保证】</b><br>
 * RabbitMQ 是 <b>at-least-once（至少一次）</b>投递模型：消息可能被投递多次
 * （网络抖动、消费者处理超时重投、消费者重启等）。本表通过
 * {@code event_id} 唯一索引保证：同一个事件只能被成功消费一次。</p>
 *
 * <p><b>【工作原理】</b><br>
 * 消费者处理消息前，先往本表 INSERT 一条记录（eventId 作为唯一键）。
 * 如果插入成功 → 这是第一次消费，继续处理业务；
 * 如果插入触发唯一索引冲突 → 之前已经处理过，直接跳过。
 * 业务处理失败时，记录随事务一起回滚，消息重新投递后可以重试。</p>
 *
 * <p><b>【为什么没有 deleted 字段？】</b><br>
 * 消费记录只增不改，是纯日志性质的表（表设计如此，不要添加不存在的字段）。</p>
 */
@Data
@TableName("message_consume_record")
public class MessageConsumeRecord {

    /** 主键 ID，由数据库自增生成。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 事件唯一 ID（UUID 字符串，由生产者生成，全局唯一）。
     * 对应数据库 {@code uk_event_id} 唯一索引，是消费幂等的核心字段。
     */
    private String eventId;

    /** 消费的队列名称（方便排查是哪条队列的消息出问题）。 */
    private String queueName;

    /**
     * 消费状态：1成功 2失败。
     * 正常流程都是 1；失败重试的场景下，最终成功也记为 1。
     */
    private Integer consumeStatus;

    /** 失败时的错误信息（消费失败时记录，便于排查）。 */
    private String errorMsg;

    /** 消费时间（数据库默认 CURRENT_TIMESTAMP）。 */
    private LocalDateTime consumeTime;
}