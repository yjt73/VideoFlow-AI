package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.VideoContext;
import com.example.server.service.AnalysisArtifactService;
import com.example.server.service.AnalysisCheckpointService;
import com.example.server.service.CheckpointKey;
import com.example.server.service.Hashing;
import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AiAnalysisStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import com.example.server.service.AiAnalysisResult;
import com.example.server.service.StorageAccessService;

@Service
public class AiService {

    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired
    @Qualifier("defaultAiStrategy")
    private AiAnalysisStrategy aiAnalysisStrategy;

    // 【关键】必须注入 Redis 工具！
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private VideoContextService videoContextService;

    @Autowired
    private AgentLoopService agentLoopService;

    @Autowired
    private StorageAccessService storageAccessService;

    @Autowired
    private AnalysisArtifactService artifactService;

    @Autowired
    private AnalysisCheckpointService checkpointService;

    @Value("${ai.pipeline.model-version:v5-default}")
    private String pipelineModelVersion;

    public AiAnalysisResult analyze(Long mediaId, String userGoal) {
        System.out.println(" [线程池] 开始处理任务，ID: " + mediaId);

        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "媒体不存在");
        }

        String goalHash = Hashing.sha256(userGoal);
        String inputHash = mediaFile.getContentHash() == null
                ? String.valueOf(mediaId)
                : mediaFile.getContentHash();
        CheckpointKey contextKey = new CheckpointKey(
                null,
                mediaId,
                "VIDEO_ANALYSIS",
                "CONTEXT_BUILT",
                goalHash,
                inputHash,
                pipelineModelVersion
        );
        VideoContext videoContext = checkpointService.findCompletedArtifact(contextKey)
                .map(artifactId -> artifactService.read(artifactId, VideoContext.class))
                .orElseGet(() -> {
                    VideoContext built = videoContextService.build(
                            storageAccessService.internalGetUrl(mediaId),
                            userGoal
                    );
                    long artifactId = artifactService.save(
                            mediaId,
                            "VIDEO_CONTEXT",
                            "CONTEXT_BUILT",
                            built
                    );
                    checkpointService.markCompleted(
                            contextKey,
                            artifactId,
                            Hashing.sha256(built.toString())
                    );
                    return built;
                });
        AgentState agentState = agentLoopService.run(videoContext);
        String markdown = agentState.result().toMarkdown();
        long resultArtifactId = artifactService.save(
                mediaId,
                "ANALYSIS_RESULT",
                "ANALYSIS_COMPLETED",
                agentState.result()
        );
        checkpointService.markCompleted(
                new CheckpointKey(
                        null,
                        mediaId,
                        "VIDEO_ANALYSIS",
                        "ANALYSIS_COMPLETED",
                        goalHash,
                        inputHash,
                        pipelineModelVersion
                ),
                resultArtifactId,
                Hashing.sha256(markdown)
        );
        return new AiAnalysisResult(
                videoContext.transcriptText(),
                markdown
        );
    }



    //异步提取全文 (专门负责提取文字)
    @Async("aiTaskExecutor")
    public void asyncTranscribe(Long mediaId) {
        System.out.println(" [线程池] 开始全文提取任务，ID: " + mediaId);

        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) return;

        try {
            //只做语音转文字
            String text = aiAnalysisStrategy.transcribe(
                    storageAccessService.internalGetUrl(mediaId).toString()
            );
            mediaFile.setTranscriptText(text);

            //保存数据库
            mediaFileMapper.updateById(mediaFile);

            //强制删除 Redis 缓存
            String userIdStr = (mediaFile.getUserId() == null) ? "anon" : String.valueOf(mediaFile.getUserId());
            String cacheKey = "media:list:user:" + userIdStr;
            redisTemplate.delete(cacheKey);

            System.out.println(" [线程池] 全文提取完成，缓存已清除！Key: " + cacheKey);

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println(" [线程池] 提取失败: " + e.getMessage());
        }
    }
}
