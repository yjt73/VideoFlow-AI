package com.example.server.service;

/** 表示重试无法修复的任务输入或业务错误。 */
public class PermanentTaskException extends RuntimeException {

    public PermanentTaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
