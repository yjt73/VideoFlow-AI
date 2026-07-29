package com.example.server.storage.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.storage.domain.StorageObject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

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

    @Update("""
            UPDATE storage_objects
            SET reference_count = reference_count - 1,
                status = CASE
                    WHEN reference_count = 1 THEN 'DELETE_PENDING'
                    ELSE status
                END,
                next_delete_at = CASE
                    WHEN reference_count = 1 THEN CURRENT_TIMESTAMP
                    ELSE next_delete_at
                END,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{objectId}
              AND reference_count > 0
            """)
    int decrementReference(@Param("objectId") long objectId);

    @Delete("""
            DELETE FROM storage_objects
            WHERE id = #{objectId}
              AND reference_count = 0
              AND status IN ('DELETE_PENDING', 'DELETE_FAILED')
            """)
    int deleteIfUnreferenced(@Param("objectId") long objectId);

    @Update("""
            UPDATE storage_objects
            SET status = 'DELETE_FAILED',
                delete_attempts = delete_attempts + 1,
                last_error = #{error},
                next_delete_at = #{nextDeleteAt},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{objectId}
              AND reference_count = 0
            """)
    int recordDeleteFailure(
            @Param("objectId") long objectId,
            @Param("error") String error,
            @Param("nextDeleteAt") LocalDateTime nextDeleteAt
    );
}
