package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import com.example.server.service.mode.ModeProfile;
import com.example.server.utils.DeepSeekUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentLoopServiceTest {

    private DeepSeekUtils deepSeekUtils;
    private LongVideoContextService contextService;
    private AgentCheckpointService checkpointService;
    private AgentExecutionBudget budget;
    private AgentTelemetry telemetry;
    private EvidenceVerificationService evidenceService;
    private AgentLoopService service;
    private VideoContext context;
    private ModeProfile profile;
    private AgentState.AgentPlan plan;
    private AnalysisResult result;
    private AgentState.CriticResult passed;
    private AgentState.CriticResult failed;

    @BeforeEach
    void setUp() {
        deepSeekUtils = mock(DeepSeekUtils.class);
        contextService = mock(LongVideoContextService.class);
        checkpointService = mock(AgentCheckpointService.class);
        budget = new AgentExecutionBudget(120_000L, 50_000L, 0.0d);
        telemetry = new AgentTelemetry();
        evidenceService = new EvidenceVerificationService();
        service = new AgentLoopService(
                deepSeekUtils, contextService, checkpointService, budget, telemetry,
                evidenceService, 2);
        context = new VideoContext(
                "video.mp4", "理解上传流程",
                List.of(new VideoContext.VideoSegment(
                        0L, 1_000L, "upload video successfully",
                        List.of(), List.of("frame-1"))));
        profile = new ModeProfile(AnalysisMode.GENERAL, "通用", "", "", "", List.of());
        plan = new AgentState.AgentPlan("理解上传流程", List.of("提取上传流程"));
        result = new AnalysisResult(
                "上传流程", List.of("upload video successfully"),
                List.of(new AnalysisResult.Evidence(
                        500L, "ASR", "upload video successfully", "upload video successfully")),
                List.of());
        passed = new AgentState.CriticResult(true, List.of(), List.of(), List.of(), List.of());
        failed = new AgentState.CriticResult(
                false, List.of("补充证据"), List.of(), List.of("缺少证据"), List.of(500L));
        when(contextService.selectRelevant(any(VideoContext.class))).thenReturn(context);
        when(deepSeekUtils.plan(any(VideoContext.class), anyString())).thenReturn(plan);
        when(deepSeekUtils.execute(any(VideoContext.class), any(AgentState.AgentPlan.class), any(), anyString()))
                .thenReturn(result);
        when(deepSeekUtils.critique(any(VideoContext.class), any(AgentState.AgentPlan.class), eq(result), anyString()))
                .thenReturn(passed);
    }

    @Test
    void runsPlannerExecutorCriticAndPersistsEveryCheckpoint() {
        AgentState state = service.run(7L, context, profile);

        assertThat(state.result()).isEqualTo(result);
        assertThat(state.critique().passed()).isTrue();
        verify(checkpointService).savePlan(7L, context.userGoal(), AnalysisMode.GENERAL, plan);
        verify(checkpointService).saveExecutionState(eq(7L), any(AgentState.class), eq(AnalysisMode.GENERAL));
        verify(checkpointService).saveCriticState(eq(7L), any(AgentState.class), eq(AnalysisMode.GENERAL));
        verify(checkpointService).saveResult(eq(7L), any(AgentState.class), eq(AnalysisMode.GENERAL));
    }

    @Test
    void returnsTerminalCheckpointWithoutCallingTheModel() {
        AgentState terminal = new AgentState(context.userGoal(), plan, result, passed, 1);
        when(checkpointService.loadResult(7L, context.userGoal(), AnalysisMode.GENERAL))
                .thenReturn(terminal);

        AgentState state = service.run(7L, context, profile);

        assertThat(state).isEqualTo(terminal);
        verifyNoInteractions(deepSeekUtils, contextService);
    }

    @Test
    void resumesExecutorDraftDirectlyAtCritic() {
        AgentState draft = new AgentState(context.userGoal(), plan, result, null, 1);
        when(checkpointService.loadResult(7L, context.userGoal(), AnalysisMode.GENERAL)).thenReturn(null);
        when(checkpointService.loadExecutionState(7L, context.userGoal(), AnalysisMode.GENERAL))
                .thenReturn(draft);
        when(checkpointService.loadPlan(7L, context.userGoal(), AnalysisMode.GENERAL)).thenReturn(plan);

        AgentState state = service.run(7L, context, profile);

        assertThat(state.critique().passed()).isTrue();
        verify(deepSeekUtils, never()).execute(any(), any(), any(), anyString());
        verify(deepSeekUtils).critique(any(), eq(plan), eq(result), anyString());
    }

    @Test
    void revisesPlanAfterFailedCriticAndContinues() {
        when(deepSeekUtils.critique(any(), any(AgentState.AgentPlan.class), eq(result), anyString()))
                .thenReturn(failed)
                .thenReturn(passed);
        AgentState.AgentPlan revised = new AgentState.AgentPlan(
                context.userGoal(), List.of("重新核验上传流程"));
        when(deepSeekUtils.revisePlan(any(), eq(plan), eq(failed), anyString())).thenReturn(revised);

        AgentState state = service.run(7L, context, profile);

        assertThat(state.critique().passed()).isTrue();
        verify(deepSeekUtils).revisePlan(any(), eq(plan), eq(failed), anyString());
        verify(deepSeekUtils, times(2)).execute(any(), any(), any(), anyString());
    }
}
