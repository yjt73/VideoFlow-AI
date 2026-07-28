package com.example.server.task.service;

/** AI 计算完成但尚未提交到媒体记录的结果。 */
public record AiAnalysisResult(
        String transcriptText,
        String summary
) {
}
