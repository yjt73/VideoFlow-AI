package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.MediaFile;
import org.apache.ibatis.annotations.Mapper;

/** 提供媒体文件表的基础 CRUD 操作。 */
@Mapper
public interface MediaFileMapper extends BaseMapper<MediaFile> {

}
