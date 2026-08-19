package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.MediaFile;
import com.example.server.task.domain.AnalysisTask;
import com.example.server.task.persistence.AnalysisTaskMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VideoQaToolService {

    private static final Pattern TIMESTAMPED_LINE = Pattern.compile(
            "^\\s*\\[(\\d{2}):(\\d{2}):(\\d{2})]\\s*(.+)$|^\\s*\\[(\\d{2}):(\\d{2})]\\s*(.+)$"
    );

    private final AnalysisTaskMapper analysisTaskMapper;

    public VideoQaToolService(AnalysisTaskMapper analysisTaskMapper) {
        this.analysisTaskMapper = analysisTaskMapper;
    }

    public VideoQaToolResult querySubtitleByKeyword(MediaFile mediaFile, String question) {
        Set<String> keywords = keywords(question);
        List<VideoQaCitation> citations = new ArrayList<>();
        String transcript = nullToBlank(mediaFile.getTranscriptText());
        for (String line : transcript.split("\\R")) {
            if (matchesAny(line, keywords)) {
                citations.add(toCitation(line, "ASR"));
            }
            if (citations.size() >= 5) {
                break;
            }
        }
        if (citations.isEmpty() && matchesAny(nullToBlank(mediaFile.getAiSummary()), keywords)) {
            citations.add(new VideoQaCitation(null, "SUMMARY", trim(nullToBlank(mediaFile.getAiSummary()), 500)));
        }
        return new VideoQaToolResult(
                "query_subtitle_by_keyword",
                citations.isEmpty() ? "没有找到直接命中的字幕或摘要" : "找到 " + citations.size() + " 条相关片段",
                citations
        );
    }

    public VideoQaToolResult loadVideoSegments(MediaFile mediaFile) {
        List<VideoQaCitation> citations = new ArrayList<>();
        String transcript = nullToBlank(mediaFile.getTranscriptText());
        for (String line : transcript.split("\\R")) {
            if (!line.isBlank()) {
                citations.add(toCitation(line, "ASR"));
            }
            if (citations.size() >= 10) {
                break;
            }
        }
        return new VideoQaToolResult("load_video_segments", "加载视频前 " + citations.size() + " 个文本片段", citations);
    }

    public VideoQaToolResult queryTaskStatus(Long mediaId, Long userId) {
        if (analysisTaskMapper == null) {
            return new VideoQaToolResult("query_task_status", "任务状态暂不可用", List.of());
        }
        AnalysisTask task = analysisTaskMapper.selectOne(new QueryWrapper<AnalysisTask>()
                .eq("media_id", mediaId)
                .eq("user_id", userId)
                .orderByDesc("created_at")
                .last("LIMIT 1"));
        if (task == null) {
            return new VideoQaToolResult("query_task_status", "暂无分析任务", List.of());
        }
        return new VideoQaToolResult(
                "query_task_status",
                "最近任务状态：" + task.getStatus() + "，阶段：" + task.getCurrentStage(),
                List.of()
        );
    }

    private VideoQaCitation toCitation(String line, String source) {
        Matcher matcher = TIMESTAMPED_LINE.matcher(line);
        if (matcher.matches()) {
            if (matcher.group(1) != null) {
                long timestampMs = (
                        Long.parseLong(matcher.group(1)) * 3600
                                + Long.parseLong(matcher.group(2)) * 60
                                + Long.parseLong(matcher.group(3))
                ) * 1000;
                return new VideoQaCitation(timestampMs, source, matcher.group(4).trim());
            }
            long timestampMs = (
                    Long.parseLong(matcher.group(5)) * 60
                            + Long.parseLong(matcher.group(6))
            ) * 1000;
            return new VideoQaCitation(timestampMs, source, matcher.group(7).trim());
        }
        return new VideoQaCitation(null, source, trim(line.trim(), 500));
    }

    private Set<String> keywords(String question) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : nullToBlank(question)
                .replaceAll("[\\p{Punct}，。！？；：“”‘’、]", " ")
                .split("\\s+")) {
            String normalized = token.trim()
                    .replace("是什么", "")
                    .replace("讲了什么", "")
                    .replace("有哪些", "")
                    .replace("什么", "")
                    .toLowerCase(Locale.ROOT);
            if (normalized.length() >= 2) {
                result.add(normalized);
            }
        }
        if (result.isEmpty() && question != null && !question.isBlank()) {
            result.add(question.trim().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private boolean matchesAny(String text, Set<String> keywords) {
        String normalized = nullToBlank(text).toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(normalized::contains);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private String trim(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
