package com.example.server.storage.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.storage.domain.MultipartUploadSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MultipartUploadSessionMapper extends BaseMapper<MultipartUploadSession> {

    @Update("""
            UPDATE multipart_upload_sessions
            SET status = 'COMPLETING',
                updated_at = CURRENT_TIMESTAMP
            WHERE session_id = #{sessionId}
              AND user_id = #{userId}
              AND status = 'INITIATED'
              AND expires_at > CURRENT_TIMESTAMP
            """)
    int claimCompleting(@Param("sessionId") String sessionId, @Param("userId") Long userId);

    @Update("""
            UPDATE multipart_upload_sessions
            SET status = 'INITIATED',
                updated_at = CURRENT_TIMESTAMP
            WHERE session_id = #{sessionId}
              AND status = 'COMPLETING'
            """)
    int restoreInitiated(@Param("sessionId") String sessionId);

    @Update("""
            UPDATE multipart_upload_sessions
            SET status = 'FAILED',
                updated_at = CURRENT_TIMESTAMP
            WHERE session_id = #{sessionId}
            """)
    int markFailed(@Param("sessionId") String sessionId, @Param("reason") String reason);

    @Update("""
            UPDATE multipart_upload_sessions
            SET status = 'COMPLETED',
                media_id = #{mediaId},
                updated_at = CURRENT_TIMESTAMP
            WHERE session_id = #{sessionId}
              AND status = 'COMPLETING'
            """)
    int markCompleted(@Param("sessionId") String sessionId, @Param("mediaId") Long mediaId);

    @Update("""
            UPDATE multipart_upload_sessions
            SET status = 'ABORTED',
                updated_at = CURRENT_TIMESTAMP
            WHERE session_id = #{sessionId}
              AND status = 'INITIATED'
            """)
    int markAborted(@Param("sessionId") String sessionId);

    @Update("""
            UPDATE multipart_upload_sessions
            SET status = 'EXPIRED',
                updated_at = CURRENT_TIMESTAMP
            WHERE session_id = #{sessionId}
              AND status = 'INITIATED'
            """)
    int markExpired(@Param("sessionId") String sessionId);
}
