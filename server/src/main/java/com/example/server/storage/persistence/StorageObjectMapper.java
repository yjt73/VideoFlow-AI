package com.example.server.storage.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.storage.domain.StorageObject;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StorageObjectMapper extends BaseMapper<StorageObject> {
}
