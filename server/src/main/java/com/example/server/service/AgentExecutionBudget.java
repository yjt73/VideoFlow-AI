package com.example.server.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentExecutionBudget {

    private final long defaultMaxDurationMs;
    private final long maxEstimatedTokens;
    private final double maxEstimatedCost;
    private final ThreadLocal<Scope> currentScope = new ThreadLocal<>();

    public AgentExecutionBudget(
            @Value("${agent.budget.max-duration-ms:120000}") long defaultMaxDurationMs,
            @Value("${agent.budget.max-estimated-tokens:50000}") long maxEstimatedTokens,
            @Value("${agent.budget.max-estimated-cost:0}") double maxEstimatedCost) {
        this.defaultMaxDurationMs = defaultMaxDurationMs;
        this.maxEstimatedTokens = maxEstimatedTokens;
        this.maxEstimatedCost = maxEstimatedCost;
    }

    public Scope open() {
        return open(defaultMaxDurationMs);
    }

    public Scope open(long maxDurationMs) {
        long durationMs = Math.max(0L, maxDurationMs);
        Scope previous = currentScope.get();
        Scope scope = new Scope(
                previous,
                System.nanoTime() + durationMs * 1_000_000L,
                maxEstimatedTokens,
                maxEstimatedCost);
        currentScope.set(scope);
        return scope;
    }

    public void addEstimatedTokens(long tokens) {
        Scope scope = currentScope.get();
        if (scope != null) scope.estimatedTokens += Math.max(0L, tokens);
    }

    public void addEstimatedCost(double cost) {
        Scope scope = currentScope.get();
        if (scope != null) scope.estimatedCost += Math.max(0.0d, cost);
    }

    public void check(String stage) {
        Scope scope = currentScope.get();
        if (scope == null) return;
        if (System.nanoTime() >= scope.deadlineNanos) {
            throw new BudgetExceededException(stage, "deadline exceeded");
        }
        if (scope.maxEstimatedTokens > 0 && scope.estimatedTokens > scope.maxEstimatedTokens) {
            throw new BudgetExceededException(stage, "estimated token limit exceeded");
        }
        if (scope.maxEstimatedCost > 0 && scope.estimatedCost > scope.maxEstimatedCost) {
            throw new BudgetExceededException(stage, "estimated cost limit exceeded");
        }
    }

    public final class Scope implements AutoCloseable {

        private final Scope previous;
        private final long deadlineNanos;
        private final long maxEstimatedTokens;
        private final double maxEstimatedCost;
        private long estimatedTokens;
        private double estimatedCost;

        private Scope(Scope previous,
                      long deadlineNanos,
                      long maxEstimatedTokens,
                      double maxEstimatedCost) {
            this.previous = previous;
            this.deadlineNanos = deadlineNanos;
            this.maxEstimatedTokens = maxEstimatedTokens;
            this.maxEstimatedCost = maxEstimatedCost;
        }

        @Override
        public void close() {
            if (currentScope.get() == this) {
                currentScope.set(previous);
            }
        }
    }

    public static class BudgetExceededException extends RuntimeException {

        public BudgetExceededException(String stage, String reason) {
            super("Agent budget exceeded at " + stage + ": " + reason);
        }
    }
}
