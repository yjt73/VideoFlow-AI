package com.example.server.service;

import com.example.server.dto.VideoContext;
import com.example.server.utils.AliyunAsrUtils;
import com.example.server.utils.OcrUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VideoContextService {

    private static final long SEGMENT_MS = 60_000L;
    private static final Pattern PTS_TIME = Pattern.compile("pts_time:([0-9.]+)");

    @Autowired
    private AliyunAsrUtils aliyunAsrUtils;

    @Autowired
    private OcrUtils ocrUtils;

    @Autowired
    @Qualifier("aiPipelineExecutor")
    private Executor aiPipelineExecutor;

    public VideoContext build(URI videoUrl, String userGoal) {
        String videoPath = videoUrl.toString();
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "video-context-" + UUID.randomUUID());
        try {
            Files.createDirectories(workDir);
            CompletableFuture<List<TranscriptPart>> transcriptFuture =
                    CompletableFuture.supplyAsync(
                            () -> runStage(() -> transcribeBySegments(videoPath, workDir.resolve("audio"))),
                            aiPipelineExecutor
                    );
            CompletableFuture<List<FramePart>> frameFuture =
                    CompletableFuture.supplyAsync(
                            () -> runStage(() -> extractKeyFrames(videoPath, workDir.resolve("frames"))),
                            aiPipelineExecutor
                    );
            List<TranscriptPart> transcripts = transcriptFuture.join();
            List<FramePart> frames = frameFuture.join();
            return new VideoContext(videoPath, userGoal, merge(transcripts, frames));
        } catch (Exception e) {
            throw new IllegalStateException("VideoContext 构建失败", e);
        } finally {
            deleteDirectory(workDir);
        }
    }

    public VideoContext build(String videoPath, String userGoal) {
        return build(URI.create(videoPath), userGoal);
    }

    public VideoContext build(String videoPath, String userGoal, String traceId) {
        return build(videoPath, userGoal);
    }

    public void deleteEvidenceFrames(VideoContext context) {
        // 当前实现将帧文件保存在临时目录并在 build 结束时清理，此处保留统一服务契约。
    }

    private List<TranscriptPart> transcribeBySegments(String videoPath, Path audioDir) throws Exception {
        Files.createDirectories(audioDir);
        Path outputPattern = audioDir.resolve("audio_%03d.mp3");
        runCommand(List.of(
                "ffmpeg", "-y", "-i", videoPath,
                "-vn", "-acodec", "libmp3lame",
                "-f", "segment", "-segment_time", "60", "-reset_timestamps", "1",
                outputPattern.toString()
        ), null, true);

        List<Path> audioFiles;
        try (var paths = Files.list(audioDir)) {
            audioFiles = paths.filter(Files::isRegularFile).sorted().toList();
        }

        List<TranscriptPart> result = new ArrayList<>();
        for (int i = 0; i < audioFiles.size(); i++) {
            String text = aliyunAsrUtils.audioToText(audioFiles.get(i).toString());
            if (text != null && !text.isBlank()) {
                result.add(new TranscriptPart(i * SEGMENT_MS, (i + 1) * SEGMENT_MS, text));
            }
        }
        return result;
    }

    private <T> T runStage(CheckedSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception exception) {
            throw new CompletionException(exception);
        }
    }

    private List<FramePart> extractKeyFrames(String videoPath, Path frameDir) throws Exception {
        Files.createDirectories(frameDir);
        List<Long> timestamps = new ArrayList<>();
        runCommand(List.of(
                "ffmpeg", "-y", "-i", videoPath,
                "-vf", "select=eq(n\\,0)+gt(scene\\,0.35),showinfo",
                "-vsync", "vfr",
                frameDir.resolve("frame_%06d.jpg").toString()
        ), timestamps, false);

        List<Path> frameFiles;
        try (var paths = Files.list(frameDir)) {
            frameFiles = paths.filter(Files::isRegularFile).sorted().toList();
        }

        List<FramePart> result = new ArrayList<>();
        for (int i = 0; i < frameFiles.size(); i++) {
            long timestampMs = i < timestamps.size() ? timestamps.get(i) : i * SEGMENT_MS;
            String ocrText = ocrUtils.recognize(frameFiles.get(i).toFile());
            result.add(new FramePart(timestampMs, ocrText, frameFiles.get(i).getFileName().toString()));
        }
        return result;
    }

    List<VideoContext.VideoSegment> merge(List<TranscriptPart> transcripts, List<FramePart> frames) {
        Map<Long, SegmentParts> buckets = new TreeMap<>();
        for (TranscriptPart transcript : transcripts) {
            SegmentParts bucket = buckets.computeIfAbsent(transcript.startMs(),
                    start -> new SegmentParts(start, transcript.endMs()));
            bucket.transcript = transcript.text();
            bucket.endMs = Math.max(bucket.endMs, transcript.endMs());
        }
        for (FramePart frame : frames) {
            long start = Math.max(0, frame.timestampMs()) / SEGMENT_MS * SEGMENT_MS;
            SegmentParts bucket = buckets.computeIfAbsent(start,
                    ignored -> new SegmentParts(start, start + SEGMENT_MS));
            if (frame.ocrText() != null && !frame.ocrText().isBlank()) bucket.ocrTexts.add(frame.ocrText());
            bucket.evidenceFrames.add(frame.frameName());
        }
        return buckets.values().stream().map(bucket -> new VideoContext.VideoSegment(
                bucket.startMs, bucket.endMs, bucket.transcript,
                bucket.ocrTexts, bucket.evidenceFrames)).toList();
    }

    private static final class SegmentParts {
        private final long startMs;
        private long endMs;
        private String transcript = "";
        private final List<String> ocrTexts = new ArrayList<>();
        private final List<String> evidenceFrames = new ArrayList<>();

        private SegmentParts(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private void runCommand(List<String> command, List<Long> timestamps, boolean allowNoAudio) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean noStream = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("does not contain any stream")) noStream = true;
                if (timestamps != null && line.contains("showinfo")) {
                    Matcher matcher = PTS_TIME.matcher(line);
                    if (matcher.find()) {
                        timestamps.add((long) (Double.parseDouble(matcher.group(1)) * 1000));
                    }
                }
            }
        }
        if (!process.waitFor(15, TimeUnit.MINUTES)
                || (process.exitValue() != 0 && !(allowNoAudio && noStream))) {
            process.destroyForcibly();
            throw new IllegalStateException("FFmpeg 执行失败");
        }
    }

    private void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (Exception ignored) {
        }
    }

    record TranscriptPart(long startMs, long endMs, String text) {
    }

    record FramePart(long timestampMs, String ocrText, String frameName) {
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
