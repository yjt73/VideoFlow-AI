package com.example.server.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class OperationMetricsService {

    private final MeterRegistry meterRegistry;

    @Autowired
    public OperationMetricsService(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(meterRegistryProvider.getIfAvailable(CompositeMeterRegistry::new));
    }

    public OperationMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void increment(String operation, String outcome) {
        meterRegistry.counter(
                "dovideo.operation.count",
                "operation", operation,
                "outcome", outcome
        ).increment();
    }

    public void recordDuration(String operation, String outcome, Duration duration) {
        Timer.builder("dovideo.operation.duration")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(duration);
    }
}
