package com.example.server.dto;

import java.util.List;

/**
 * 将视频中的语音和画面信息按时间轴整理成 Agent 可消费的统一上下文。
 */
/** 汇总视频转写、关键帧 OCR 和用户分析目标的上下文。 */
public record VideoContext(
        String source,
        String userGoal,
        List<VideoSegment> segments
) {
    public record VideoSegment(
            long startMs,
            long endMs,
            String transcript,
            List<String> ocrTexts,
            List<String> evidenceFrames
    ) {
    }

    /** 拼接全部片段的转写文本，供模型快速读取。 */
    public String transcriptText() {
        return segments.stream()
                .map(VideoSegment::transcript)
                .filter(text -> text != null && !text.isBlank())
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
    }
}
