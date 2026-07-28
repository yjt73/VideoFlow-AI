package com.example.server.task.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.task.domain.AnalysisTask;
import org.apache.ibatis.annotations.Mapper;

/** 分析任务持久化入口。 */
@Mapper
public interface AnalysisTaskMapper extends BaseMapper<AnalysisTask> {
}
