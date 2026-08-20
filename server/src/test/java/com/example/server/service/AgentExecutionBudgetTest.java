package com.example.server.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutionBudgetTest {

    @Test
    void stopsTheCurrentRunWhenItsDeadlineIsExceeded() {
        AgentExecutionBudget budget = new AgentExecutionBudget(10_000L, 100L, 1.0d);

        try (AgentExecutionBudget.Scope ignored = budget.open(0L)) {
            assertThatThrownBy(() -> budget.check("planner"))
                    .isInstanceOf(AgentExecutionBudget.BudgetExceededException.class)
                    .hasMessageContaining("planner");
        }
    }

    @Test
    void stopsTheCurrentRunWhenEstimatedTokensExceedTheLimit() {
        AgentExecutionBudget budget = new AgentExecutionBudget(10_000L, 10L, 1.0d);

        try (AgentExecutionBudget.Scope ignored = budget.open(10_000L)) {
            budget.addEstimatedTokens(11L);
            assertThatThrownBy(() -> budget.check("executor"))
                    .isInstanceOf(AgentExecutionBudget.BudgetExceededException.class);
        }
    }

    @Test
    void stopsTheCurrentRunWhenEstimatedCostExceedsTheLimit() {
        AgentExecutionBudget budget = new AgentExecutionBudget(10_000L, 100L, 0.5d);

        try (AgentExecutionBudget.Scope ignored = budget.open(10_000L)) {
            budget.addEstimatedCost(0.6d);
            assertThatThrownBy(() -> budget.check("critic"))
                    .isInstanceOf(AgentExecutionBudget.BudgetExceededException.class);
        }
    }
}
