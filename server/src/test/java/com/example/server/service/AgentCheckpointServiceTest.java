package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AgentState;
import com.example.server.dto.TaskStage;
import com.example.server.mapper.AgentCheckpointMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentCheckpointServiceTest {

    private StringRedisTemplate redisTemplate;
    private HashOperations<String, Object, Object> hashOperations;
    private AgentCheckpointMapper checkpointMapper;
    private AgentCheckpointService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        hashOperations = mock(HashOperations.class);
        checkpointMapper = mock(AgentCheckpointMapper.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        service = new AgentCheckpointService(redisTemplate, new ObjectMapper(), checkpointMapper);
    }

    @Test
    void savesAndLoadsPlanFromDatabaseWhenCacheMisses() {
        AgentState.AgentPlan plan = new AgentState.AgentPlan("理解视频", List.of("提取主题"));
        service.savePlan(7L, "理解视频", AnalysisMode.LEARNING, plan);

        when(hashOperations.get(anyString(), eq("plan"))).thenReturn(null);
        when(checkpointMapper.findPayload(eq(7L), anyString()))
                .thenReturn("{\"understoodGoal\":\"理解视频\",\"tasks\":[\"提取主题\"]}");

        assertThat(service.loadPlan(7L, "理解视频", AnalysisMode.LEARNING)).isEqualTo(plan);
        verify(checkpointMapper).upsert(eq(7L), contains(":plan"), eq("PLAN_COMPLETED"), anyString());
    }

    @Test
    void keepsExecutionAndCriticStatesSeparatedAndRestorable() {
        AgentState execution = state("执行目标", false, 1);
        AgentState critic = state("审查目标", true, 2);

        service.saveExecutionState(9L, execution, AnalysisMode.GENERAL);
        service.saveCriticState(9L, critic, AnalysisMode.GENERAL);

        when(hashOperations.get(anyString(), eq("execution"))).thenReturn(null);
        when(hashOperations.get(anyString(), eq("critique"))).thenReturn(null);
        when(checkpointMapper.findPayload(eq(9L), contains(":execution")))
                .thenReturn(new ObjectMapper().valueToTree(execution).toString());
        when(checkpointMapper.findPayload(eq(9L), contains(":critique")))
                .thenReturn(new ObjectMapper().valueToTree(critic).toString());

        assertThat(service.loadExecutionState(9L, "执行目标", AnalysisMode.GENERAL)).isEqualTo(execution);
        assertThat(service.loadCriticState(9L, "审查目标", AnalysisMode.GENERAL)).isEqualTo(critic);
        verify(checkpointMapper, atLeast(2)).upsert(eq(9L), anyString(), anyString(), any());
    }

    @Test
    void restoresTerminalResultAndUsesDifferentKeysForGoalsAndModes() {
        AgentState result = state("结论目标", true, 3);
        service.saveResult(11L, result, AnalysisMode.REVIEW);

        when(hashOperations.get(anyString(), eq("result"))).thenReturn(null);
        when(checkpointMapper.findPayload(eq(11L), contains(":result")))
                .thenReturn(new ObjectMapper().valueToTree(result).toString());

        assertThat(service.loadResult(11L, "结论目标", AnalysisMode.REVIEW)).isEqualTo(result);

        service.savePlan(11L, "结论目标", AnalysisMode.GENERAL,
                new AgentState.AgentPlan("普通模式", List.of("普通任务")));
        service.savePlan(11L, "另一个目标", AnalysisMode.REVIEW,
                new AgentState.AgentPlan("另一个目标", List.of("另一任务")));

        var keys = mockingDetails(checkpointMapper).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("upsert"))
                .map(invocation -> (String) invocation.getArguments()[1])
                .filter(key -> !key.endsWith(":stage"))
                .distinct()
                .toList();
        assertThat(keys).hasSize(3);
    }

    @Test
    void malformedPayloadReturnsEmptyValueInsteadOfBreakingRecovery() {
        when(hashOperations.get(anyString(), eq("plan"))).thenReturn("{broken-json");
        when(checkpointMapper.findPayload(eq(3L), anyString())).thenReturn("{broken-json");

        assertThat(service.loadPlan(3L, "坏数据", AnalysisMode.GENERAL)).isNull();
    }

    @Test
    void repeatedWritesReplaceTheSameCheckpoint() {
        AgentState.AgentPlan first = new AgentState.AgentPlan("目标", List.of("第一版"));
        AgentState.AgentPlan second = new AgentState.AgentPlan("目标", List.of("第二版"));

        service.savePlan(5L, "目标", AnalysisMode.GENERAL, first);
        service.savePlan(5L, "目标", AnalysisMode.GENERAL, second);

        verify(checkpointMapper, times(2))
                .upsert(eq(5L), anyString(), eq("PLAN_COMPLETED"), anyString());
        verify(hashOperations, times(2)).put(anyString(), eq("plan"), anyString());
    }

    @Test
    void persistsAndLoadsTheLatestStageForRecovery() {
        service.savePlan(13L, "阶段目标", AnalysisMode.GENERAL,
                new AgentState.AgentPlan("阶段目标", List.of("规划")));
        when(hashOperations.get(anyString(), eq("stage"))).thenReturn(null);
        when(checkpointMapper.findStage(eq(13L), contains(":stage"))).thenReturn("PLAN_COMPLETED");

        assertThat(service.loadStage(13L, "阶段目标", AnalysisMode.GENERAL))
                .isEqualTo(TaskStage.PLAN_COMPLETED);
        verify(checkpointMapper).upsert(eq(13L), contains(":stage"), eq("PLAN_COMPLETED"), isNull());
    }

    private AgentState state(String goal, boolean passed, int round) {
        return new AgentState(
                goal,
                new AgentState.AgentPlan(goal, List.of("执行")),
                new com.example.server.dto.AnalysisResult(
                        "结果", List.of("结论"),
                        List.of(new com.example.server.dto.AnalysisResult.Evidence(1L, "ASR", "证据")),
                        List.of("建议")),
                new AgentState.CriticResult(passed, List.of("反馈"), List.of(), List.of(), List.of()),
                round);
    }
}
