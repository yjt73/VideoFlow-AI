package com.example.server.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RagSegmenter {

    private static final Pattern TIMESTAMPED_LINE = Pattern.compile(
            "^\\s*\\[(\\d{2}):(\\d{2}):(\\d{2})]\\s*(.+)$|^\\s*\\[(\\d{2}):(\\d{2})]\\s*(.+)$"
    );

    public List<RagTextSegment> splitTranscript(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return List.of();
        }
        List<LinePart> lines = transcript.lines()
                .map(this::parseLine)
                .filter(part -> !part.content().isBlank())
                .toList();
        List<RagTextSegment> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            LinePart current = lines.get(i);
            Long endMs = i + 1 < lines.size() && lines.get(i + 1).startMs() != null
                    ? lines.get(i + 1).startMs()
                    : current.startMs();
            result.add(new RagTextSegment(i, current.startMs(), endMs, "TRANSCRIPT", current.content()));
        }
        return result;
    }

    public List<RagTextSegment> splitSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return List.of();
        }
        List<String> paragraphs = List.of(summary.split("\\R\\s*\\R")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        List<RagTextSegment> result = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            result.add(new RagTextSegment(i, null, null, "SUMMARY", paragraphs.get(i)));
        }
        return result;
    }

    private LinePart parseLine(String line) {
        Matcher matcher = TIMESTAMPED_LINE.matcher(line);
        if (!matcher.matches()) {
            return new LinePart(null, line.trim());
        }
        if (matcher.group(1) != null) {
            long timestampMs = (
                    Long.parseLong(matcher.group(1)) * 3600
                            + Long.parseLong(matcher.group(2)) * 60
                            + Long.parseLong(matcher.group(3))
            ) * 1000;
            return new LinePart(timestampMs, matcher.group(4).trim());
        }
        long timestampMs = (
                Long.parseLong(matcher.group(5)) * 60
                        + Long.parseLong(matcher.group(6))
        ) * 1000;
        return new LinePart(timestampMs, matcher.group(7).trim());
    }

    private record LinePart(Long startMs, String content) {
    }
}
