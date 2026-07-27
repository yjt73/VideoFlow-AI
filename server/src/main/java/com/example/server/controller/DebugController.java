package com.example.server.controller;

import com.example.server.api.ApiResponse;
import com.example.server.api.BusinessException;
import com.example.server.api.DependencyUnavailableException;
import com.example.server.api.ErrorCode;
import com.example.server.auth.AuthenticatedUser;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AiService;
import com.example.server.strategy.AiAnalysisStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate; // 【修复】导入 Redis 类
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit; //导入时间单位

/** 提供开发阶段的视频分析、转写和音频下载调试接口。 */
@RestController
@RequestMapping("/debug")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class DebugController {

    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired
    @Qualifier("defaultAiStrategy")
    private AiAnalysisStrategy aiAnalysisStrategy;

    @Autowired
    private AiService aiService;


    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate;

    @Autowired
    private org.redisson.api.RedissonClient redissonClient;

    //AI总结接口(分布式锁 + 限流 + MQ)
    @GetMapping("/ai")
    /** 直接触发指定媒体的 AI 分析，便于开发阶段排查问题。 */
    public ApiResponse<String> aiAnalyze(
            @RequestParam Long id,
            @RequestParam(defaultValue = "理解视频核心内容并生成结构化分析报告") String goal,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        String activeKey = null;

        try {
            if (goal.isBlank() || goal.length() > 500) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "分析目标不能为空且不能超过 500 字");
            }
            MediaFile file = mediaFileMapper.selectById(id);
            if (file == null) throw new BusinessException(ErrorCode.NOT_FOUND, "媒体不存在");
            requireOwner(file, currentUser);

            // 这里演示：全局限制每分钟只能分析 10 次 (防止费用爆炸)
            String limitKey = "limit:ai:global";
            org.redisson.api.RRateLimiter rateLimiter = redissonClient.getRateLimiter(limitKey);
            //初始化：每 1 分钟产生 10 个令牌 (RateType.OVERALL 全局, OVER_CLIENT 是单机)
            rateLimiter.trySetRate(org.redisson.api.RateType.OVERALL, 10, 1, org.redisson.api.RateIntervalUnit.MINUTES);

            //尝试获取 1 个令牌
            if (!rateLimiter.tryAcquire(1)) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "系统繁忙，请稍后再试");
            }

            //查库校验
            if (file.getAiSummary() != null && file.getAiSummary().contains("正在")) {
                throw new BusinessException(ErrorCode.CONFLICT, "任务已在后台运行");
            }

            String contentHash = redisTemplate.opsForValue().get("media:md5:" + id);
            if (contentHash == null || !contentHash.matches("[a-f0-9]{32}")) {
                contentHash = "media-" + id;
            }
            activeKey = "analysis:active:" + contentHash;
            Boolean accepted = redisTemplate.opsForValue()
                    .setIfAbsent(activeKey, String.valueOf(id), 2, TimeUnit.HOURS);
            if (!Boolean.TRUE.equals(accepted)) {
                throw new BusinessException(ErrorCode.CONFLICT, "相同视频正在分析");
            }

            //更新状态
            file.setAiSummary("[MQ] 已进入消息队列，等待调度...");
            mediaFileMapper.updateById(file);
            String userIdKey = (file.getUserId() == null) ? "anon" : String.valueOf(file.getUserId());
            redisTemplate.delete("media:list:user:" + userIdKey);

            //发送消息
            AnalysisTaskMsg msg = new AnalysisTaskMsg(id, "START_ANALYSIS", contentHash, goal);
            rocketMQTemplate.convertAndSend("video-analysis-topic", msg);

            return ApiResponse.success("任务已投递至 RocketMQ");

        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception e) {
            if (activeKey != null) {
                redisTemplate.delete(activeKey);
            }
            throw new DependencyUnavailableException("分析任务依赖暂时不可用", e);
        }
    }

    //纯文字提取接口
    @GetMapping("/transcribe")
    /** 直接触发指定媒体的语音转写。 */
    public ApiResponse<String> transcribe(@RequestParam Long id,
                                          @AuthenticationPrincipal AuthenticatedUser currentUser) {
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) throw new BusinessException(ErrorCode.NOT_FOUND, "媒体不存在");
        requireOwner(mediaFile, currentUser);

        // 调用异步服务
        aiService.asyncTranscribe(id);

        return ApiResponse.success("提取任务已在后台运行");
    }

    //下载音频接口
    @GetMapping("/download")
    /** 将媒体转换出的音频作为下载响应返回。 */
    public ResponseEntity<Resource> download(@RequestParam Long id,
                                             @AuthenticationPrincipal AuthenticatedUser currentUser) throws IOException {
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) throw new BusinessException(ErrorCode.NOT_FOUND, "媒体不存在");
        if (!Objects.equals(mediaFile.getUserId(), currentUser.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问他人的媒体");
        }

        String inputPath = mediaFile.getFilePath();

        if (!inputPath.startsWith("http")) {
            if (!new File(inputPath).exists()) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "媒体源文件不存在");
            }
        }

        String outputMp3Path = System.getProperty("java.io.tmpdir") + File.separator + "download_" + UUID.randomUUID() + ".mp3";
        System.out.println("⬇ 下载请求，正在从源地址转码音频: " + inputPath);

        boolean success = runFfmpeg(inputPath, outputMp3Path);

        if (!success) throw new DependencyUnavailableException("音频转码失败");

        File mp3File = new File(outputMp3Path);
        Resource resource = new FileSystemResource(mp3File);

        String fileName = "audio.mp3";
        if (mediaFile.getFilename() != null) {
            fileName = mediaFile.getFilename().replaceAll("\\.[^.]+$", "") + ".mp3";
        }
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(resource);
    }

    private void requireOwner(MediaFile mediaFile, AuthenticatedUser currentUser) {
        if (!Objects.equals(mediaFile.getUserId(), currentUser.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问他人的媒体");
        }
    }

    /** 执行 FFmpeg 转换命令，并根据退出码判断是否成功。 */
    private boolean runFfmpeg(String inputPath, String outputPath) {
        try {
            List<String> command = new ArrayList<>();
            command.add("ffmpeg");
            command.add("-y");
            command.add("-i");
            command.add(inputPath);
            command.add("-vn");
            command.add("-acodec");
            command.add("libmp3lame");
            command.add("-q:a");
            command.add("2");
            command.add(outputPath);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();
            return process.waitFor(15, TimeUnit.MINUTES) && process.exitValue() == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
