package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentTelemetry {

    private final ThreadLocal<Map<String, Double>> current = ThreadLocal.withInitial(HashMap::new);

    public void incrementCurrent(String name) {
        valueCurrent(name, 1.0d);
    }

    public void incrementCurrent(String name, long amount) {
        valueCurrent(name, amount);
    }

    public void valueCurrent(String name, double value) {
        if (name == null || name.isBlank()) return;
        current.get().merge(name.trim(), value, Double::sum);
    }

    public Map<String, Double> snapshot() {
        return Map.copyOf(current.get());
    }

    public void resetCurrent() {
        current.remove();
    }

    public String start(Long taskId, String goal) {
        return start(taskId, goal, AnalysisMode.GENERAL);
    }

    public String start(Long taskId, String goal, AnalysisMode mode) {
        resetCurrent();
        return UUID.randomUUID().toString();
    }

    public void bind(String traceId) {
    }

    public void clear() {
        resetCurrent();
    }

    public void flush(String traceId) {
    }

    public void stage(String traceId, String stage, long startedNanos, boolean success) {
        incrementCurrent("stage." + stage + (success ? ".success" : ".failure"));
    }

    public void increment(String traceId, String metric, long amount) {
        incrementCurrent(metric, amount);
    }

    public void failCurrentStage(String stage, long startedNanos) {
        incrementCurrent("stage." + stage + ".failure");
    }

    public void modelCall(String stage, String prompt, String response,
                          double inputPricePerMillion, double outputPricePerMillion,
                          long startedNanos) {
        incrementCurrent("modelCalls");
        incrementCurrent("inputTokensEstimated", estimateTokens(prompt));
        incrementCurrent("outputTokensEstimated", estimateTokens(response));
    }

    public BudgetUsage currentUsage() {
        Map<String, Double> values = current.get();
        return new BudgetUsage(
                values.getOrDefault("inputTokensEstimated", 0D).longValue()
                        + values.getOrDefault("outputTokensEstimated", 0D).longValue(),
                values.getOrDefault("estimatedCost", 0D));
    }

    public Map<String, Object> latest(Long taskId, String goal) {
        return latest(taskId, goal, AnalysisMode.GENERAL);
    }

    public Map<String, Object> latest(Long taskId, String goal, AnalysisMode mode) {
        return new HashMap<>(current.get());
    }

    public void deleteTask(Long taskId) {
        resetCurrent();
    }

    private long estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }

    public record BudgetUsage(long estimatedTokens, double estimatedCost) {
    }
}
