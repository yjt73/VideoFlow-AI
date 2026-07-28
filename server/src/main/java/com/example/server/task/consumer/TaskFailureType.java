package com.example.server.task.consumer;

/** 决定消费异常是否应交给 RocketMQ 再次投递。 */
public enum TaskFailureType {
    RETRYABLE,
    PERMANENT
}
