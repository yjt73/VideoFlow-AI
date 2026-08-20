package com.example.server.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AnalysisCheckpointServiceTest {

    @Test
    void mapsCheckpointFieldsToTheCorrectArtifactColumns() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AnalysisCheckpointService service = new AnalysisCheckpointService(jdbcTemplate);
        CheckpointKey key = new CheckpointKey(
                "task-1", 7L, "VIDEO_ANALYSIS", "PLAN",
                "goal-hash", "input-hash", "model-v1");

        service.markCompleted(key, 42L, "checksum");

        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), values.capture());
        assertThat(values.getValue()).containsExactly(
                "task-1", "VIDEO_ANALYSIS", "PLAN", "goal-hash", "input-hash", "model-v1",
                "checksum", 42L);
    }
}
