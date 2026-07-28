package com.example.server.task.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.task.domain.AnalysisTaskFailure;
import org.apache.ibatis.annotations.Mapper;

/** 分析任务失败历史持久化入口。 */
@Mapper
public interface AnalysisTaskFailureMapper extends BaseMapper<AnalysisTaskFailure> {
}
