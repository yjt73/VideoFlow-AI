package com.example.server.task.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.task.domain.OutboxEvent;
import org.apache.ibatis.annotations.Mapper;

/** Outbox 事件持久化入口。 */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {
}
