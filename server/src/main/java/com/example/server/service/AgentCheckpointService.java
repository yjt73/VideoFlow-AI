package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AgentState;
import com.example.server.dto.TaskStage;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.mapper.AgentCheckpointMapper;
import com.example.server.utils.AnalysisTaskKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;
import java.util.List;
import java.util.Set;

@Service
public class AgentCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(AgentCheckpointService.class);
    private static final Duration CACHE_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentCheckpointMapper checkpointMapper;

    public AgentCheckpointService(StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  AgentCheckpointMapper checkpointMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.checkpointMapper = checkpointMapper;
    }

    public VideoContext loadContext(Long mediaId) {
        return read(mediaId, "media:context", "agent:checkpoint:" + mediaId, "context", VideoContext.class)
                .orElse(null);
    }

    public AgentState loadResult(Long mediaId, String goal) {
        return loadResult(mediaId, goal, AnalysisMode.GENERAL);
    }

    public AgentState.AgentPlan loadPlan(Long mediaId, String goal) {
        return loadPlan(mediaId, goal, AnalysisMode.GENERAL);
    }

    public AgentState loadCriticState(Long mediaId, String goal) {
        return loadCriticState(mediaId, goal, AnalysisMode.GENERAL);
    }

    public TaskStage loadStage(Long mediaId, String goal) {
        return loadStage(mediaId, goal, AnalysisMode.GENERAL);
    }

    public List<VideoChunk> loadChunks(Long mediaId) {
        Object cachedChunks = read(mediaId, "media:chunks", "agent:checkpoint:" + mediaId,
                "chunks", Object.class).orElse(null);
        if (cachedChunks == null) return List.of();
        return objectMapper.convertValue(cachedChunks,
                objectMapper.getTypeFactory().constructCollectionType(List.class, VideoChunk.class));
    }

    public void saveContext(Long mediaId, VideoContext context) {
        write(mediaId, "media:context", "agent:checkpoint:" + mediaId,
                "context", TaskStage.CONTEXT_COMPLETED, context);
    }

    public void saveChunks(Long mediaId, List<VideoChunk> chunks) {
        write(mediaId, "media:chunks", "agent:checkpoint:" + mediaId,
                "chunks", TaskStage.CHUNKS_COMPLETED, chunks == null ? List.of() : chunks);
    }

    public void savePlan(Long mediaId, String goal, AgentState.AgentPlan plan) {
        savePlan(mediaId, goal, AnalysisMode.GENERAL, plan);
    }

    public void saveExecutionState(Long mediaId, AgentState state) {
        saveExecutionState(mediaId, state, AnalysisMode.GENERAL);
    }

    public void saveCriticState(Long mediaId, AgentState state) {
        saveCriticState(mediaId, state, AnalysisMode.GENERAL);
    }

    public void saveResult(Long mediaId, AgentState state) {
        saveResult(mediaId, state, AnalysisMode.GENERAL);
    }

    public AgentState.AgentPlan loadPlan(Long mediaId, String goal, AnalysisMode mode) {
        return read(mediaId, goalCheckpoint(goal, mode, "plan"), cacheKey(mediaId, goal, mode),
                "plan", AgentState.AgentPlan.class).orElse(null);
    }

    public AgentState loadExecutionState(Long mediaId, String goal, AnalysisMode mode) {
        return read(mediaId, goalCheckpoint(goal, mode, "execution"), cacheKey(mediaId, goal, mode),
                "execution", AgentState.class).orElse(null);
    }

    public AgentState loadCriticState(Long mediaId, String goal, AnalysisMode mode) {
        return read(mediaId, goalCheckpoint(goal, mode, "critique"), cacheKey(mediaId, goal, mode),
                "critique", AgentState.class).orElse(null);
    }

    public AgentState loadResult(Long mediaId, String goal, AnalysisMode mode) {
        return read(mediaId, goalCheckpoint(goal, mode, "result"), cacheKey(mediaId, goal, mode),
                "result", AgentState.class).orElse(null);
    }

    public void savePlan(Long mediaId, String goal, AnalysisMode mode, AgentState.AgentPlan plan) {
        write(mediaId, goalCheckpoint(goal, mode, "plan"), cacheKey(mediaId, goal, mode),
                "plan", TaskStage.PLAN_COMPLETED, plan);
    }

    public void saveExecutionState(Long mediaId, AgentState state, AnalysisMode mode) {
        write(mediaId, goalCheckpoint(state.goal(), mode, "execution"), cacheKey(mediaId, state.goal(), mode),
                "execution", TaskStage.EXECUTOR_COMPLETED, state);
    }

    public void saveCriticState(Long mediaId, AgentState state, AnalysisMode mode) {
        TaskStage stage = state.critique() != null && state.critique().passed()
                ? TaskStage.CRITIC_PASSED
                : TaskStage.CRITIC_RETRY_REQUIRED;
        write(mediaId, goalCheckpoint(state.goal(), mode, "critique"), cacheKey(mediaId, state.goal(), mode),
                "critique", stage, state);
    }

    public void saveResult(Long mediaId, AgentState state, AnalysisMode mode) {
        TaskStage stage = state.critique() != null && state.critique().passed()
                ? TaskStage.ANALYSIS_COMPLETED
                : TaskStage.ANALYSIS_COMPLETED_WITH_WARNINGS;
        write(mediaId, goalCheckpoint(state.goal(), mode, "result"), cacheKey(mediaId, state.goal(), mode),
                "result", stage, state);
    }

    public void saveStage(Long mediaId, String goal, TaskStage stage) {
        saveStage(mediaId, goal, AnalysisMode.GENERAL, stage);
    }

    public void saveStage(Long mediaId, String goal, AnalysisMode mode, TaskStage stage) {
        String redisKey = cacheKey(mediaId, goal, mode);
        checkpointMapper.upsert(mediaId, goalCheckpoint(goal, mode, "stage"), stage.name(), null);
        hash().put(redisKey, "stage", stage.name());
        redisTemplate.expire(redisKey, CACHE_TTL);
    }

    public void stageRevision(Long mediaId, String goal, AgentState.AgentPlan plan) {
        stageRevision(mediaId, goal, AnalysisMode.GENERAL, plan);
    }

    public void stageRevision(Long mediaId, String goal, AnalysisMode mode, AgentState.AgentPlan plan) {
        write(mediaId, "revision:" + AnalysisTaskKeys.goalDigest(goal, mode),
                "agent:revision:" + mediaId, "plan", TaskStage.REVISION_PENDING, plan);
    }

    public boolean beginStagedRevision(Long mediaId, String goal) {
        return beginStagedRevision(mediaId, goal, AnalysisMode.GENERAL);
    }

    public boolean beginStagedRevision(Long mediaId, String goal, AnalysisMode mode) {
        return checkpointMapper.findPayload(mediaId,
                "revision:" + AnalysisTaskKeys.goalDigest(goal, mode)) != null;
    }

    public void completeStagedRevision(Long mediaId, String goal, AnalysisMode mode) {
        saveStage(mediaId, goal, mode, TaskStage.REVISION_APPLIED);
    }

    public void cancelStagedRevision(Long mediaId, String goal) {
        cancelStagedRevision(mediaId, goal, AnalysisMode.GENERAL);
    }

    public void cancelStagedRevision(Long mediaId, String goal, AnalysisMode mode) {
        checkpointMapper.delete(mediaId, "revision:" + AnalysisTaskKeys.goalDigest(goal, mode));
    }

    public void saveFeedback(AgentFeedback feedback) {
        if (feedback == null || feedback.mediaId() == null) return;
        try {
            redisTemplate.opsForList().rightPush(
                    "agent:feedback:" + feedback.mediaId(),
                    objectMapper.writeValueAsString(feedback.normalized()));
        } catch (Exception exception) {
            log.debug("agent_feedback_cache_write_failed mediaId={}", feedback.mediaId(), exception);
        }
    }

    public List<AgentFeedback> loadFeedback(Long mediaId) {
        try {
            List<String> values = redisTemplate.opsForList().range("agent:feedback:" + mediaId, 0, -1);
            if (values == null) return List.of();
            return values.stream().map(value -> {
                try {
                    return objectMapper.readValue(value, AgentFeedback.class);
                } catch (JsonProcessingException exception) {
                    return null;
                }
            }).filter(java.util.Objects::nonNull).toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    public void saveFailure(Long mediaId, String goal, AnalysisMode mode, TaskStage failedStage, Exception error) {
        String key = cacheKey(mediaId, goal, mode);
        write(mediaId, goalCheckpoint(goal, mode, "failure"), key, "failure",
                failedStage == null ? TaskStage.FAILED : failedStage,
                new FailurePayload(error));
    }

    public TaskStage loadStage(Long mediaId, String goal, AnalysisMode mode) {
        try {
            Object cached = hash().get(cacheKey(mediaId, goal, mode), "stage");
            TaskStage stage = TaskStage.from(cached == null ? null : cached.toString());
            if (stage != null) return stage;
        } catch (RuntimeException exception) {
            log.debug("agent_checkpoint_stage_cache_read_failed mediaId={}", mediaId, exception);
        }
        try {
            return TaskStage.from(checkpointMapper.findStage(
                    mediaId, goalCheckpoint(goal, mode, "stage")));
        } catch (RuntimeException exception) {
            log.debug("agent_checkpoint_stage_db_read_failed mediaId={}", mediaId, exception);
            return null;
        }
    }

    @Transactional
    public void deleteMedia(Long mediaId) {
        checkpointMapper.deleteByMediaId(mediaId);
        try {
            Set<String> keys = redisTemplate.keys("agent:checkpoint:" + mediaId + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (RuntimeException exception) {
            log.warn("agent_checkpoint_cache_cleanup_failed mediaId={}", mediaId, exception);
        }
    }

    private void write(Long mediaId,
                       String checkpointName,
                       String redisKey,
                       String field,
                       TaskStage stage,
                       Object value) {
        try {
            String payload = objectMapper.writeValueAsString(value);
            checkpointMapper.upsert(mediaId, checkpointName, stage.name(), payload);
            String stageCheckpointName = checkpointName.substring(0, checkpointName.lastIndexOf(':')) + ":stage";
            checkpointMapper.upsert(mediaId, stageCheckpointName, stage.name(), null);
            HashOperations<String, Object, Object> operations = hash();
            operations.put(redisKey, field, payload);
            operations.put(redisKey, "stage", stage.name());
            redisTemplate.expire(redisKey, CACHE_TTL);
        } catch (Exception exception) {
            throw new IllegalStateException("保存 Agent Checkpoint 失败", exception);
        }
    }

    private <T> Optional<T> read(Long mediaId,
                                 String checkpointName,
                                 String redisKey,
                                 String field,
                                 Class<T> type) {
        try {
            Object cached = hash().get(redisKey, field);
            if (cached != null) {
                return Optional.of(objectMapper.readValue(cached.toString(), type));
            }
        } catch (Exception exception) {
            log.warn("agent_checkpoint_cache_read_failed mediaId={} field={}", mediaId, field, exception);
        }
        try {
            String persisted = checkpointMapper.findPayload(mediaId, checkpointName);
            if (persisted == null || persisted.isBlank()) return Optional.empty();
            T value = objectMapper.readValue(persisted, type);
            try {
                hash().put(redisKey, field, persisted);
                redisTemplate.expire(redisKey, CACHE_TTL);
            } catch (RuntimeException cacheException) {
                log.debug("agent_checkpoint_cache_write_failed mediaId={} field={}",
                        mediaId, field, cacheException);
            }
            return Optional.of(value);
        } catch (Exception exception) {
            log.warn("agent_checkpoint_read_failed mediaId={} checkpoint={}",
                    mediaId, checkpointName, exception);
            return Optional.empty();
        }
    }

    private HashOperations<String, Object, Object> hash() {
        return redisTemplate.opsForHash();
    }

    private String cacheKey(Long mediaId, String goal, AnalysisMode mode) {
        return "agent:checkpoint:" + mediaId + ":goal:" + AnalysisTaskKeys.goalDigest(goal, mode);
    }

    private String goalCheckpoint(String goal, AnalysisMode mode, String field) {
        return "goal:" + AnalysisTaskKeys.goalDigest(goal, mode) + ":" + field;
    }

    private record FailurePayload(String type, String message) {
        private FailurePayload(Exception error) {
            this(error.getClass().getSimpleName(),
                    error.getMessage() == null ? "" : error.getMessage());
        }
    }
}
