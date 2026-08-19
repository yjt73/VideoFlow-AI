package com.example.server.service;

import java.util.List;

/** 五分钟检索块；摘要用于召回，正文保留原始时间证据。 */
public record RagChunk(
        Long startMs,
        Long endMs,
        String content,
        List<RagTextSegment> sourceSegments
) {
    public RagChunk {
        content = content == null ? "" : content.trim();
        sourceSegments = sourceSegments == null ? List.of() : List.copyOf(sourceSegments);
    }
}
