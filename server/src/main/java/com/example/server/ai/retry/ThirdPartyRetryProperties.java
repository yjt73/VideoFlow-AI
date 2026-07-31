package com.example.server.ai.retry;

import java.time.Duration;

public record ThirdPartyRetryProperties(
        int maxAttempts,
        Duration baseDelay,
        Duration maxDelay,
        double jitterRatio
) {

    public static ThirdPartyRetryProperties defaults() {
        return new ThirdPartyRetryProperties(
                3,
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                0.30
        );
    }

    public static ThirdPartyRetryProperties noJitter(
            int maxAttempts,
            Duration baseDelay,
            Duration maxDelay
    ) {
        return new ThirdPartyRetryProperties(maxAttempts, baseDelay, maxDelay, 0);
    }
}
