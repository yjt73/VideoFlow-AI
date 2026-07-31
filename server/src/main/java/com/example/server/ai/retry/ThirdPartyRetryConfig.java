package com.example.server.ai.retry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ThirdPartyRetryConfig {

    private static final Logger log =
            LoggerFactory.getLogger(ThirdPartyRetryConfig.class);

    @Bean
    public ThirdPartyRetryExecutor thirdPartyRetryExecutor() {
        return new ThirdPartyRetryExecutor(
                ThirdPartyRetryProperties.defaults(),
                delayMillis -> {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new ThirdPartyTransientException(
                                "Retry sleep interrupted",
                                exception
                        );
                    }
                },
                retryLog -> log.warn(
                        "event=third_party_retry api={} taskId={} attempt={} elapsedMs={} failureType={} message={}",
                        retryLog.apiName(),
                        retryLog.taskId(),
                        retryLog.attempt(),
                        retryLog.elapsedMillis(),
                        retryLog.failureType(),
                        retryLog.message()
                )
        );
    }

    public static Duration retryAfterSeconds(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
