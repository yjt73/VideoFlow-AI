package com.example.server.task.service;

import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static com.example.server.task.domain.AnalysisTaskStatus.RUNNING;
import static com.example.server.task.domain.AnalysisTaskStatus.SUCCESS;

/** 只有任务仍在运行时，才原子提交状态和最终媒体结果。 */
@Service
public class AnalysisTaskCompletionService {

    private final AnalysisTaskStateService stateService;
    private final MediaFileMapper mediaFileMapper;
    private final StringRedisTemplate redisTemplate;

    public AnalysisTaskCompletionService(
            AnalysisTaskStateService stateService,
            MediaFileMapper mediaFileMapper,
            StringRedisTemplate redisTemplate
    ) {
        this.stateService = stateService;
        this.mediaFileMapper = mediaFileMapper;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public boolean complete(
            String taskId,
            Long mediaId,
            AiAnalysisResult result
    ) {
        boolean claimed = stateService.transition(
                taskId,
                Set.of(RUNNING),
                SUCCESS,
                TaskProgressUpdate.reset(100, "COMPLETED")
        );
        if (!claimed) {
            return false;
        }
        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "分析任务对应的媒体不存在"
            );
        }
        media.setTranscriptText(result.transcriptText());
        media.setAiSummary(result.summary());
        mediaFileMapper.updateById(media);
        String userKey = media.getUserId() == null
                ? "anon"
                : String.valueOf(media.getUserId());
        redisTemplate.delete("media:list:user:" + userKey);
        return true;
    }
}
