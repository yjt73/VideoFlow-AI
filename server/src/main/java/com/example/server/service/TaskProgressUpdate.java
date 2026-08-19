package com.example.server.service;

/** 一次状态转换同时写入的进度、阶段和错误信息。 */
public record TaskProgressUpdate(
        Integer progress,
        String currentStage,
        String errorCode,
        String errorMessage,
        boolean clearError
) {

    public static TaskProgressUpdate none() {
        return new TaskProgressUpdate(null, null, null, null, false);
    }

    public static TaskProgressUpdate progress(int progress, String stage) {
        return new TaskProgressUpdate(progress, stage, null, null, false);
    }

    public static TaskProgressUpdate failure(
            int progress,
            String stage,
            String errorCode,
            String errorMessage
    ) {
        return new TaskProgressUpdate(
                progress,
                stage,
                errorCode,
                errorMessage,
                false
        );
    }

    public static TaskProgressUpdate reset(int progress, String stage) {
        return new TaskProgressUpdate(progress, stage, null, null, true);
    }
}
