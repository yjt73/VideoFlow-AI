package com.example.server.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OperationMetricsServiceTest {

    @Test
    void recordsCountersAndTimersWithOperationAndOutcomeTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OperationMetricsService metricsService = new OperationMetricsService(registry);

        metricsService.increment("rag.search", "success");
        metricsService.recordDuration("rag.search", "success", Duration.ofMillis(25));

        assertThat(registry.counter("dovideo.operation.count", "operation", "rag.search", "outcome", "success").count())
                .isEqualTo(1);
        assertThat(registry.timer("dovideo.operation.duration", "operation", "rag.search", "outcome", "success").count())
                .isEqualTo(1);
    }
}
