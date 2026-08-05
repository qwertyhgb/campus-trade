package com.ming.campustrade.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ming.campustrade.entity.MessageConsumeRecord;

/**
 * 消息消费记录数据访问层（Mapper / DAO），操作 {@code message_consume_record} 表。
 *
 * <p>继承 {@link BaseMapper} 即可获得单表常用 CRUD 能力。
 * 消费幂等的核心动作就是 {@code insert}：利用 {@code uk_event_id} 唯一索引，
 * 插入冲突说明事件已消费过，消费者直接跳过。</p>
 *
 * @author ming
 */
@Mapper
public interface MessageConsumeRecordMapper extends BaseMapper<MessageConsumeRecord> {
}