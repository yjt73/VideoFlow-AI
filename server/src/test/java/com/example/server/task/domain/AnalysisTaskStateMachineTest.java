package com.example.server.task.domain;

import org.junit.jupiter.api.Test;

import static com.example.server.task.domain.AnalysisTaskStatus.CANCELLED;
import static com.example.server.task.domain.AnalysisTaskStatus.PENDING;
import static com.example.server.task.domain.AnalysisTaskStatus.QUEUED;
import static com.example.server.task.domain.AnalysisTaskStatus.RETRYING;
import static com.example.server.task.domain.AnalysisTaskStatus.RUNNING;
import static com.example.server.task.domain.AnalysisTaskStatus.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisTaskStateMachineTest {

    private final AnalysisTaskStateMachine machine = new AnalysisTaskStateMachine();

    @Test
    void allowsTheMainFlowAndRetryFlow() {
        assertThat(machine.canTransition(PENDING, QUEUED)).isTrue();
        assertThat(machine.canTransition(QUEUED, RUNNING)).isTrue();
        assertThat(machine.canTransition(RUNNING, SUCCESS)).isTrue();
        assertThat(machine.canTransition(RUNNING, RETRYING)).isTrue();
        assertThat(machine.canTransition(RETRYING, RUNNING)).isTrue();
    }

    @Test
    void terminalStatesCannotRestartWithoutAnExplicitRecoveryTransition() {
        assertThat(machine.canTransition(SUCCESS, RUNNING)).isFalse();
        assertThat(machine.canTransition(CANCELLED, SUCCESS)).isFalse();
    }
}
