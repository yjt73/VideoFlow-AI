package com.example.server.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** 参考长视频 Chunk 思路，将有时间戳的片段按五分钟聚合。 */
@Component
public class RagChunker {

    private static final long CHUNK_MS = 5 * 60 * 1000L;

    public List<RagChunk> chunk(List<RagTextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        List<RagTextSegment> timestamped = segments.stream()
                .filter(segment -> segment.startMs() != null)
                .sorted(Comparator.comparing(RagTextSegment::startMs))
                .toList();
        List<RagTextSegment> untimestamped = segments.stream()
                .filter(segment -> segment.startMs() == null)
                .toList();

        List<RagChunk> result = new ArrayList<>();
        if (!timestamped.isEmpty()) {
            long lastWindow = timestamped.get(timestamped.size() - 1).startMs() / CHUNK_MS;
            for (long window = 0; window <= lastWindow; window++) {
                long start = window * CHUNK_MS;
                long end = start + CHUNK_MS;
                List<RagTextSegment> members = timestamped.stream()
                        .filter(segment -> segment.startMs() >= start && segment.startMs() < end)
                        .toList();
                if (!members.isEmpty()) {
                    long actualEnd = members.stream()
                            .map(RagTextSegment::endMs)
                            .filter(value -> value != null)
                            .max(Long::compareTo)
                            .orElse(end);
                    result.add(toChunk(start, actualEnd, members));
                }
            }
        }
        if (!untimestamped.isEmpty()) {
            result.add(toChunk(null, null, untimestamped));
        }
        return result;
    }

    private RagChunk toChunk(Long startMs, Long endMs, List<RagTextSegment> members) {
        return new RagChunk(
                startMs,
                endMs,
                members.stream().map(RagTextSegment::content).collect(Collectors.joining("\n")),
                members
        );
    }
}
