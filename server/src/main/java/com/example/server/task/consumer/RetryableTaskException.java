package com.example.server.task.consumer;

/** 向 RocketMQ 明确表示当前消息需要稍后重试。 */
public class RetryableTaskException extends RuntimeException {

    public RetryableTaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
