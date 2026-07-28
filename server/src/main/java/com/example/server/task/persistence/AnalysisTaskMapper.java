package com.example.server.task.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.task.domain.AnalysisTask;
import com.example.server.task.service.TaskProgressUpdate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 分析任务持久化入口。 */
@Mapper
public interface AnalysisTaskMapper extends BaseMapper<AnalysisTask> {

    @Update("""
            UPDATE analysis_tasks
            SET compensation_count = compensation_count + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE task_id = #{taskId}
              AND status = 'PENDING'
              AND compensation_count = #{expectedCount}
            """)
    int incrementCompensation(
            @Param("taskId") String taskId,
            @Param("expectedCount") Integer expectedCount
    );

    /** 使用当前状态和版本号原子推进任务，防止多个消费者同时成功抢占。 */
    @Update("""
            <script>
            UPDATE analysis_tasks
            <set>
                status = #{toStatus},
                version = version + 1,
                retry_count = CASE
                    WHEN #{toStatus} = 'RETRYING'
                    THEN retry_count + 1
                    ELSE retry_count
                END,
                updated_at = CURRENT_TIMESTAMP,
                <if test="update.progress != null">
                    progress = #{update.progress},
                </if>
                <if test="update.currentStage != null">
                    current_stage = #{update.currentStage},
                </if>
                <choose>
                    <when test="update.clearError">
                        error_code = NULL,
                        error_message = NULL,
                    </when>
                    <when test="update.errorCode != null">
                        error_code = #{update.errorCode},
                        error_message = #{update.errorMessage},
                    </when>
                </choose>
                started_at = CASE
                    WHEN #{toStatus} = 'RUNNING' AND started_at IS NULL
                    THEN CURRENT_TIMESTAMP
                    ELSE started_at
                END,
                finished_at = CASE
                    WHEN #{toStatus} IN ('SUCCESS', 'FAILED', 'DEAD', 'CANCELLED')
                    THEN CURRENT_TIMESTAMP
                    ELSE finished_at
                END
            </set>
            WHERE task_id = #{taskId}
              AND version = #{version}
              AND status = #{fromStatus}
            </script>
            """)
    int transition(
            @Param("taskId") String taskId,
            @Param("version") Long version,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("update") TaskProgressUpdate update
    );
}
