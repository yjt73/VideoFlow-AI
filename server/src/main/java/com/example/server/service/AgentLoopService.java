package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import com.example.server.service.mode.ModeProfile;
import com.example.server.utils.DeepSeekUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 可恢复的 Planner -> Executor -> Critic Agent 编排器。 */
@Service
public class AgentLoopService {

    private static final int MAX_PLAN_TASKS = 5;

    private final DeepSeekUtils deepSeekUtils;
    private final LongVideoContextService longVideoContextService;
    private final AgentCheckpointService checkpointService;
    private final AgentExecutionBudget executionBudget;
    private final AgentTelemetry telemetry;
    private final EvidenceVerificationService evidenceVerificationService;
    private final int maxRounds;

    @Autowired
    public AgentLoopService(DeepSeekUtils deepSeekUtils,
                            LongVideoContextService longVideoContextService,
                            AgentCheckpointService checkpointService,
                            AgentExecutionBudget executionBudget,
                            AgentTelemetry telemetry,
                            EvidenceVerificationService evidenceVerificationService,
                            @Value("${agent.budget.max-rounds:2}") int maxRounds) {
        if (maxRounds < 1) throw new IllegalArgumentException("Agent 最大轮次必须大于 0");
        this.deepSeekUtils = deepSeekUtils;
        this.longVideoContextService = longVideoContextService;
        this.checkpointService = checkpointService;
        this.executionBudget = executionBudget;
        this.telemetry = telemetry;
        this.evidenceVerificationService = evidenceVerificationService;
        this.maxRounds = maxRounds;
    }

    public AgentState run(VideoContext context) {
        return run(null, context, generalProfile());
    }

    public AgentState run(Long mediaId, VideoContext context) {
        return run(mediaId, context, generalProfile());
    }

    public AgentState run(Long mediaId, VideoContext context, ModeProfile profile) {
        validateContext(context);
        ModeProfile resolvedProfile = profile == null ? generalProfile() : profile;
        AnalysisMode mode = resolvedProfile.mode();
        try (AgentExecutionBudget.Scope ignored = executionBudget.open()) {
            return runWithinBudget(mediaId, context, resolvedProfile, mode);
        }
    }

    private AgentState runWithinBudget(Long mediaId,
                                       VideoContext originalContext,
                                       ModeProfile profile,
                                       AnalysisMode mode) {
        AgentState terminal = mediaId == null
                ? null
                : checkpointService.loadResult(mediaId, originalContext.userGoal(), mode);
        if (isTerminal(terminal)) {
            telemetry.incrementCurrent("terminalCheckpointHits");
            return terminal;
        }

        VideoContext context = mediaId == null
                ? longVideoContextService.selectRelevant(originalContext)
                : longVideoContextService.selectRelevant(mediaId, originalContext);
        if (context == null) {
            context = longVideoContextService.selectRelevant(originalContext);
        }
        executionBudget.check("planner");
        AgentState.AgentPlan plan = mediaId == null
                ? null
                : checkpointService.loadPlan(mediaId, context.userGoal(), mode);
        if (plan == null) plan = deepSeekUtils.plan(context, profile.planInstruction());
        if (!isPlanValid(plan)) {
            plan = deepSeekUtils.repairPlan(context, plan, profile.planInstruction());
            telemetry.incrementCurrent("planRepairs");
        }
        validatePlan(plan);
        if (mediaId != null) checkpointService.savePlan(mediaId, context.userGoal(), mode, plan);

        AgentState state = loadRecoverableState(mediaId, context.userGoal(), mode, plan);
        if (state != null && state.result() != null && state.critique() == null) {
            state = critiqueDraft(mediaId, context, plan, state.result(), state.round(), profile, mode);
            if (state.critique().passed()) {
                if (mediaId != null) checkpointService.saveResult(mediaId, state, mode);
                return state;
            }
        }
        if (state != null && state.result() != null && state.critique() != null
                && !state.critique().passed() && state.round() < maxRounds) {
            context = refreshContext(originalContext, context, state.critique(), mediaId);
            plan = deepSeekUtils.revisePlan(context, plan, state.critique(), profile.planInstruction());
            validatePlan(plan);
            state = new AgentState(context.userGoal(), plan, null, null, state.round());
        }

        int firstRound = state == null ? 1 : Math.max(1, state.round() + 1);
        for (int round = firstRound; round <= maxRounds; round++) {
            executionBudget.check("round-" + round);
            AnalysisResult result = deepSeekUtils.execute(
                    context, plan, state == null ? null : state.critique(), profile.executeInstruction());
            AgentState draft = new AgentState(context.userGoal(), plan, result, null, round);
            if (mediaId != null) checkpointService.saveExecutionState(mediaId, draft, mode);
            state = critiqueDraft(mediaId, context, plan, result, round, profile, mode);
            if (state.critique().passed() || round >= maxRounds) {
                if (mediaId != null) checkpointService.saveResult(mediaId, state, mode);
                return state;
            }
            context = refreshContext(originalContext, context, state.critique(), mediaId);
            plan = deepSeekUtils.revisePlan(context, plan, state.critique(), profile.planInstruction());
            validatePlan(plan);
        }
        throw new IllegalStateException("Agent 未生成结果");
    }

    private AgentState loadRecoverableState(Long mediaId, String goal, AnalysisMode mode,
                                             AgentState.AgentPlan plan) {
        if (mediaId == null) return null;
        AgentState criticState = checkpointService.loadCriticState(mediaId, goal, mode);
        if (criticState != null && criticState.plan() != null && criticState.result() != null) {
            return new AgentState(goal, plan, criticState.result(), criticState.critique(), criticState.round());
        }
        AgentState executionState = checkpointService.loadExecutionState(mediaId, goal, mode);
        if (executionState != null && executionState.result() != null) {
            return new AgentState(goal, plan, executionState.result(), executionState.critique(), executionState.round());
        }
        return null;
    }

    private AgentState critiqueDraft(Long mediaId,
                                     VideoContext context,
                                     AgentState.AgentPlan plan,
                                     AnalysisResult result,
                                     int round,
                                     ModeProfile profile,
                                     AnalysisMode mode) {
        executionBudget.check("critic");
        AgentState.CriticResult critique = deepSeekUtils.critique(
                context, plan, result, profile.criticInstruction());
        critique = enforceEvidence(context, result, critique);
        AgentState state = new AgentState(context.userGoal(), plan, result, critique, round);
        if (mediaId != null) checkpointService.saveCriticState(mediaId, state, mode);
        telemetry.incrementCurrent("criticRounds");
        if (critique.passed()) telemetry.incrementCurrent("criticPassed");
        return state;
    }

    private AgentState.CriticResult enforceEvidence(VideoContext context,
                                                     AnalysisResult result,
                                                     AgentState.CriticResult critique) {
        if (critique == null) {
            return new AgentState.CriticResult(false, java.util.List.of("Critic 未返回结果"),
                    java.util.List.of(), java.util.List.of(), java.util.List.of());
        }
        if (!critique.passed() || result == null || result.evidence() == null) return critique;
        boolean invalidEvidence = result.evidence().stream()
                .anyMatch(evidence -> !evidenceVerificationService.supported(context, evidence));
        boolean unsupportedClaim = result.conclusions().stream()
                .anyMatch(claim -> result.evidence().stream().noneMatch(
                        evidence -> evidenceVerificationService.supportsClaim(context, claim, evidence)));
        if (!invalidEvidence && !unsupportedClaim) return critique;
        return new AgentState.CriticResult(false,
                java.util.List.of("为每条结论重新绑定可核验的 ASR/OCR 时间戳证据"),
                java.util.List.of(), java.util.List.of("存在未被视频上下文支持的结论或证据"),
                result.evidence().stream().map(AnalysisResult.Evidence::timestampMs).toList());
    }

    private VideoContext refreshContext(VideoContext original,
                                        VideoContext selected,
                                        AgentState.CriticResult critique,
                                        Long mediaId) {
        if (critique.requiredTimestamps().isEmpty()) return selected;
        telemetry.incrementCurrent("evidenceRefreshes");
        VideoContext refreshed = longVideoContextService.refineForCritique(
                mediaId, original, selected, critique);
        return refreshed == null ? selected : refreshed;
    }

    private boolean isTerminal(AgentState state) {
        return state != null && state.result() != null && state.critique() != null
                && (state.critique().passed() || state.round() >= maxRounds);
    }

    private boolean isPlanValid(AgentState.AgentPlan plan) {
        return plan != null && !plan.understoodGoal().isBlank()
                && !plan.tasks().isEmpty() && plan.tasks().size() <= MAX_PLAN_TASKS
                && plan.tasks().stream().allMatch(task -> task != null && !task.isBlank());
    }

    private void validatePlan(AgentState.AgentPlan plan) {
        if (!isPlanValid(plan)) throw new IllegalStateException("Planner 返回了无效任务列表");
    }

    private void validateContext(VideoContext context) {
        if (context == null || context.userGoal() == null || context.userGoal().isBlank()
                || context.segments() == null || context.segments().isEmpty()) {
            throw new IllegalArgumentException("Agent 需要目标和至少一个视频片段");
        }
    }

    private ModeProfile generalProfile() {
        return new ModeProfile(AnalysisMode.GENERAL, "通用分析", "", "", "", java.util.List.of());
    }
}
