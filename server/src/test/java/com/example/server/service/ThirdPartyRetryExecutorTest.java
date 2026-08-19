package com.example.server.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThirdPartyRetryExecutorTest {

    @Test
    void retriesRetryableFailuresWithExponentialDelay() {
        List<Long> delays = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        ThirdPartyRetryExecutor executor = new ThirdPartyRetryExecutor(
                ThirdPartyRetryProperties.noJitter(3, Duration.ofSeconds(1), Duration.ofSeconds(30)),
                delays::add,
                RetryAttemptLogger.noop()
        );

        String result = executor.execute("ASR", "task-1", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IOException("temporary");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        assertThat(delays).containsExactly(1000L, 2000L);
    }

    @Test
    void usesRetryAfterBeforeCalculatedDelay() {
        List<Long> delays = new ArrayList<>();
        ThirdPartyRetryExecutor executor = new ThirdPartyRetryExecutor(
                ThirdPartyRetryProperties.noJitter(3, Duration.ofSeconds(1), Duration.ofSeconds(30)),
                delays::add,
                RetryAttemptLogger.noop()
        );
        AtomicInteger attempts = new AtomicInteger();

        String result = executor.execute("LLM", "task-2", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new ThirdPartyApiException("LLM", 429, "limited", Duration.ofSeconds(7));
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(delays).containsExactly(7000L);
    }

    @Test
    void doesNotRetryPermanentHttpErrors() {
        ThirdPartyRetryExecutor executor = new ThirdPartyRetryExecutor(
                ThirdPartyRetryProperties.noJitter(3, Duration.ofSeconds(1), Duration.ofSeconds(30)),
                ignored -> {
                },
                RetryAttemptLogger.noop()
        );

        assertThatThrownBy(() -> executor.execute("Embedding", "task-3", () -> {
            throw new ThirdPartyApiException("Embedding", 400, "bad request", null);
        })).isInstanceOf(ThirdPartyApiException.class);
    }
}
