package com.example.server.controller;

import com.example.server.api.ApiResponse;
import com.example.server.api.BusinessException;
import com.example.server.api.DependencyUnavailableException;
import com.example.server.api.ErrorCode;
import com.example.server.auth.AuthenticatedUser;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AiService;
import com.example.server.strategy.AiAnalysisStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
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
