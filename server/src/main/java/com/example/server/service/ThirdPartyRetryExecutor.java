package com.example.server.service;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

public class ThirdPartyRetryExecutor {

    private final ThirdPartyRetryProperties properties;
    private final RetrySleeper sleeper;
    private final RetryAttemptLogger logger;

    public ThirdPartyRetryExecutor(
            ThirdPartyRetryProperties properties,
            RetrySleeper sleeper,
            RetryAttemptLogger logger
    ) {
        this.properties = properties;
        this.sleeper = sleeper;
        this.logger = logger;
    }

    public <T> T execute(
            String apiName,
            String taskId,
            RetryableOperation<T> operation
    ) {
        long startedAt = System.nanoTime();
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                return operation.call();
            } catch (Exception exception) {
                RuntimeException failure = asRuntime(exception);
                logger.log(new RetryAttemptLog(
                        apiName,
                        taskId,
                        attempt,
                        elapsedMillis(startedAt),
                        failureType(failure),
                        safeMessage(failure)
                ));
                if (attempt >= properties.maxAttempts() || !isRetryable(failure)) {
                    throw failure;
                }
                sleeper.sleep(delayMillis(attempt, failure));
            }
        }
        throw new IllegalStateException("unreachable retry state");
    }

    private RuntimeException asRuntime(Exception exception) {
        if (exception instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new ThirdPartyTransientException(exception.getMessage(), exception);
    }

    private boolean isRetryable(Throwable failure) {
        if (failure instanceof ThirdPartyApiException apiException) {
            int status = apiException.statusCode();
            return status == 429 || status == 503 || status >= 500;
        }
        if (failure instanceof ThirdPartyTransientException) {
            return true;
        }
        Throwable cause = failure.getCause();
        return failure instanceof IOException
                || failure instanceof TimeoutException
                || cause instanceof IOException
                || cause instanceof TimeoutException;
    }

    private long delayMillis(int failedAttempt, RuntimeException failure) {
        if (failure instanceof ThirdPartyApiException apiException
                && apiException.retryAfter() != null) {
            return apiException.retryAfter().toMillis();
        }
        long calculated = properties.baseDelay().toMillis() * (1L << (failedAttempt - 1));
        long capped = Math.min(calculated, properties.maxDelay().toMillis());
        if (properties.jitterRatio() <= 0) {
            return capped;
        }
        long jitter = Math.round(capped * properties.jitterRatio());
        return capped + ThreadLocalRandom.current().nextLong(jitter + 1);
    }

    private String failureType(RuntimeException failure) {
        if (failure instanceof ThirdPartyApiException apiException) {
            return "HTTP_" + apiException.statusCode();
        }
        return failure.getClass().getSimpleName();
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }
}
