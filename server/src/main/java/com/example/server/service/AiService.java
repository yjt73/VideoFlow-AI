package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.example.server.service.mode.ModeRegistry;
import com.example.server.utils.AnalysisTaskKeys;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

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
    private LongVideoContextService longVideoContextService;

    @Autowired
    private AgentCheckpointService agentCheckpointService;

    @Autowired
    private AgentTelemetry telemetry;

    @Autowired
    private MediaService mediaService;

    @Autowired
    private TaskEventService taskEventService;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private ModeRegistry modeRegistry;

    @Value("${ai.pipeline.model-version:v5-default}")
    private String pipelineModelVersion;

    public AiAnalysisResult analyze(Long mediaId, String userGoal) {
        asyncAnalyze(mediaId, userGoal, AnalysisMode.GENERAL);
        AgentState state = agentCheckpointService.loadResult(mediaId, userGoal, AnalysisMode.GENERAL);
        VideoContext context = agentCheckpointService.loadContext(mediaId);
        if (state == null || state.result() == null || context == null) {
            throw new IllegalStateException("AI 分析未生成完整结果");
        }
        return new AiAnalysisResult(context.transcriptText(), state.result().toMarkdown());
    }

    public void asyncAnalyze(Long mediaId, String userGoal) {
        asyncAnalyze(mediaId, userGoal, AnalysisMode.GENERAL);
    }

    public void asyncAnalyze(Long mediaId, String userGoal, AnalysisMode mode) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        String traceId = telemetry.start(mediaId, userGoal, resolvedMode);
        telemetry.bind(traceId);
        TaskStage currentStage = TaskStage.VIDEO_CONTEXT;
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) {
            telemetry.flush(traceId);
            telemetry.clear();
            throw new IllegalArgumentException("media does not exist: " + mediaId);
        }
        try {
            AgentState state = agentCheckpointService.loadResult(mediaId, userGoal, resolvedMode);
            if (state != null && state.result() != null) {
                persistAgentResult(mediaFile, state);
                telemetry.increment(traceId, "checkpointHits", 1);
                return;
            }
            VideoContext context = resolveAgentContext(mediaFile, userGoal, traceId, resolvedMode);
            mediaFile.setTranscriptText(context.transcriptText());
            currentStage = TaskStage.AGENT_LOOP;
            taskEventService.publishAnalysis(mediaId, userGoal, resolvedMode,
                    TaskStatus.of(TaskStatus.State.PROCESSING, "多模态上下文已就绪，Agent 开始分析"),
                    TaskStage.AGENT_LOOP);
            long started = System.nanoTime();
            try {
                state = agentLoopService.run(mediaId, context, modeRegistry.of(resolvedMode));
                telemetry.stage(traceId, TaskStage.AGENT_LOOP.name(), started, true);
            } catch (RuntimeException exception) {
                telemetry.stage(traceId, TaskStage.AGENT_LOOP.name(), started, false);
                throw exception;
            }
            persistAgentResult(mediaFile, state);
        } catch (Exception exception) {
            try {
                agentCheckpointService.saveFailure(mediaId, userGoal, resolvedMode, currentStage, exception);
            } catch (RuntimeException checkpointError) {
                exception.addSuppressed(checkpointError);
            }
            if (exception instanceof AgentExecutionBudget.BudgetExceededException budgetExceeded) {
                throw budgetExceeded;
            }
            throw new IllegalStateException("AI analysis failed", exception);
        } finally {
            telemetry.flush(traceId);
            telemetry.clear();
        }
    }

    private VideoContext resolveAgentContext(MediaFile mediaFile,
                                             String userGoal,
                                             String traceId,
                                             AnalysisMode mode) {
        VideoContext own = agentCheckpointService.loadContext(mediaFile.getId());
        if (own != null) {
            telemetry.increment(traceId, "contextCheckpointHits", 1);
            return new VideoContext(own.source(), userGoal, own.segments());
        }
        String contentHash = AnalysisTaskKeys.normalizeContentHash(
                mediaFile.getId(), mediaService.contentHash(mediaFile.getId()));
        VideoContext reused = reuseAgentContext(mediaFile, userGoal, traceId, contentHash);
        if (reused != null) return reused;
        RLock lock = redissonClient.getLock(AnalysisTaskKeys.contextLock(contentHash));
        boolean acquired = false;
        try {
            acquired = lock.tryLock(300, TimeUnit.SECONDS);
            own = agentCheckpointService.loadContext(mediaFile.getId());
            if (own != null) return new VideoContext(own.source(), userGoal, own.segments());
            reused = reuseAgentContext(mediaFile, userGoal, traceId, contentHash);
            if (reused != null) return reused;
            if (!acquired) throw new IllegalStateException("同一视频的上下文正在构建中，稍后重试");
            taskEventService.publishAnalysis(mediaFile.getId(), userGoal, mode,
                    TaskStatus.of(TaskStatus.State.PROCESSING, "正在并行提取语音与关键帧"),
                    TaskStage.VIDEO_CONTEXT);
            long started = System.nanoTime();
            try {
                VideoContext context = videoContextService.build(mediaFile.getFilePath(), userGoal, traceId);
                agentCheckpointService.saveContext(mediaFile.getId(), context);
                redisTemplate.opsForValue().set(
                        AnalysisTaskKeys.contextOwner(contentHash), String.valueOf(mediaFile.getId()),
                        Duration.ofDays(7));
                telemetry.stage(traceId, TaskStage.VIDEO_CONTEXT.name(), started, true);
                return context;
            } catch (RuntimeException exception) {
                telemetry.stage(traceId, TaskStage.VIDEO_CONTEXT.name(), started, false);
                throw exception;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待视频上下文构建锁被中断", exception);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private VideoContext reuseAgentContext(MediaFile mediaFile,
                                           String userGoal,
                                           String traceId,
                                           String contentHash) {
        String owner = redisTemplate.opsForValue().get(AnalysisTaskKeys.contextOwner(contentHash));
        if (owner == null) return null;
        Long ownerId;
        try {
            ownerId = Long.valueOf(owner);
        } catch (NumberFormatException exception) {
            redisTemplate.delete(AnalysisTaskKeys.contextOwner(contentHash));
            return null;
        }
        if (ownerId.equals(mediaFile.getId())) return null;
        VideoContext source = agentCheckpointService.loadContext(ownerId);
        if (source == null) return null;
        VideoContext localized = new VideoContext(mediaFile.getFilePath(), userGoal, source.segments());
        agentCheckpointService.saveContext(mediaFile.getId(), localized);
        telemetry.increment(traceId, "contextContentReuses", 1);
        return localized;
    }

    private void persistAgentResult(MediaFile mediaFile, AgentState state) {
        if (state == null || state.result() == null) throw new IllegalStateException("Agent 未生成分析结果");
        mediaFile.setAiSummary(state.result().toMarkdown());
        mediaFile.setTranscriptText(agentCheckpointService.loadContext(mediaFile.getId()).transcriptText());
        mediaFileMapper.updateById(mediaFile);
        mediaService.invalidateUserList(mediaFile.getUserId());
    }



    //异步提取全文 (专门负责提取文字)
    @Async("aiTaskExecutor")
    public void asyncTranscribe(Long mediaId) {
        System.out.println(" [线程池] 开始全文提取任务，ID: " + mediaId);

        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) return;

        try {
            //只做语音转文字
            String text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath());
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

    public String followUp(Long mediaId, String originalGoal, String question) {
        return followUp(mediaId, originalGoal, question, AnalysisMode.GENERAL);
    }

    public String followUp(Long mediaId,
                           String originalGoal,
                           String question,
                           AnalysisMode mode) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        VideoContext context = agentCheckpointService.loadContext(mediaId);
        if (context == null) throw new VideoContextNotReadyException();
        String traceId = telemetry.start(mediaId, question, resolvedMode);
        telemetry.bind(traceId);
        try {
            AgentState previous = originalGoal == null
                    ? null : agentCheckpointService.loadResult(mediaId, originalGoal, resolvedMode);
            String followUpGoal = previous == null || previous.result() == null
                    ? question
                    : "原始目标：" + originalGoal + "\n已有分析："
                    + previous.result().toMarkdown().substring(0,
                    Math.min(4_000, previous.result().toMarkdown().length()))
                    + "\n当前追问：" + question;
            AgentState state = agentLoopService.run(
                    mediaId,
                    new VideoContext(context.source(), followUpGoal, context.segments()),
                    modeRegistry.of(resolvedMode));
            return state.result().toMarkdown();
        } finally {
            telemetry.flush(traceId);
            telemetry.clear();
        }
    }

    public List<VideoEvidenceHit> searchEvidence(Long mediaId, String query) {
        VideoContext context = agentCheckpointService.loadContext(mediaId);
        if (context == null) throw new VideoContextNotReadyException();
        String traceId = telemetry.start(mediaId, query);
        telemetry.bind(traceId);
        long started = System.nanoTime();
        try {
            List<VideoEvidenceHit> hits = longVideoContextService.searchEvidence(
                    mediaId, new VideoContext(context.source(), query, context.segments()));
            telemetry.stage(traceId, TaskStage.RETRIEVAL.name(), started, true);
            return hits;
        } catch (RuntimeException exception) {
            telemetry.stage(traceId, TaskStage.RETRIEVAL.name(), started, false);
            throw exception;
        } finally {
            telemetry.flush(traceId);
            telemetry.clear();
        }
    }

    public void stageRevision(AgentFeedback feedback) {
        stageRevision(feedback, AnalysisMode.GENERAL);
    }

    public void stageRevision(AgentFeedback feedback, AnalysisMode mode) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        AgentFeedback normalized = feedback.normalized(resolvedMode);
        agentCheckpointService.saveFeedback(normalized);
        String goal = normalized.correctedGoal() == null || normalized.correctedGoal().isBlank()
                ? normalized.goal() : normalized.correctedGoal().trim();
        AgentState.AgentPlan plan = normalized.correctedTasks().isEmpty()
                ? null : new AgentState.AgentPlan(goal, normalized.correctedTasks());
        agentCheckpointService.stageRevision(normalized.mediaId(), goal, resolvedMode, plan);
    }

    public String revisionGoal(AgentFeedback feedback) {
        AgentFeedback normalized = feedback.normalized();
        return normalized.correctedGoal() == null || normalized.correctedGoal().isBlank()
                ? normalized.goal() : normalized.correctedGoal();
    }

    public void cancelStagedRevision(Long mediaId, String goal) {
        cancelStagedRevision(mediaId, goal, AnalysisMode.GENERAL);
    }

    public void cancelStagedRevision(Long mediaId, String goal, AnalysisMode mode) {
        agentCheckpointService.cancelStagedRevision(mediaId, goal, mode);
    }

    public boolean reuseResult(Long mediaId, Long sourceMediaId, AgentState state) {
        return reuseResult(mediaId, sourceMediaId, state, AnalysisMode.GENERAL);
    }

    public boolean reuseResult(Long mediaId, Long sourceMediaId, AgentState state, AnalysisMode mode) {
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) throw new IllegalArgumentException("media does not exist: " + mediaId);
        VideoContext source = agentCheckpointService.loadContext(sourceMediaId);
        if (source == null) return false;
        VideoContext localized = new VideoContext(mediaFile.getFilePath(), "", source.segments());
        agentCheckpointService.saveContext(mediaId, localized);
        agentCheckpointService.saveResult(mediaId,
                new AgentState(state.goal(), state.plan(), state.result(), state.critique(), state.round()), mode);
        persistAgentResult(mediaFile, state);
        return true;
    }
}
