package com.example.server.storage.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.storage.domain.StorageObject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StorageObjectMapper extends BaseMapper<StorageObject> {

    @Update("""
            UPDATE storage_objects
            SET reference_count = reference_count + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{objectId}
              AND status = 'ACTIVE'
            """)
    int incrementReference(@Param("objectId") long objectId);
}
