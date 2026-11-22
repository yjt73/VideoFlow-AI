package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.api.ApiResponse;
import com.example.server.api.BusinessException;
import com.example.server.api.DependencyUnavailableException;
import com.example.server.api.ErrorCode;
import com.example.server.service.AuthenticatedUser;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.dto.MediaView;
import com.example.server.dto.MediaAccessView;
import com.example.server.dto.MediaSummary;
import com.example.server.common.Result;
import com.example.server.service.ChunkUploadService;
import com.example.server.service.MediaIngestService;
import com.example.server.service.MediaService;
import com.example.server.utils.YtDlpUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/media")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class MediaController {

    private static final String MEDIA_LIST_CACHE_PREFIX = "media:list:user:";

    private final MediaFileMapper mediaFileMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final YtDlpUtils ytDlpUtils;
    private final ChunkUploadService chunkUploadService;
    private final MediaIngestService mediaIngestService;
    private final MediaService mediaService;

    public MediaController(
            MediaFileMapper mediaFileMapper,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            YtDlpUtils ytDlpUtils,
            ChunkUploadService chunkUploadService,
            MediaIngestService mediaIngestService,
            MediaService mediaService
    ) {
        this.mediaFileMapper = mediaFileMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ytDlpUtils = ytDlpUtils;
        this.chunkUploadService = chunkUploadService;
        this.mediaIngestService = mediaIngestService;
        this.mediaService = mediaService;
    }

    @PostMapping("/upload-url")
    public ApiResponse<String> uploadUrl(
            @RequestParam("url") String url,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        if (url == null || url.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "URL 不能为空");
        }

        try {
            MediaFile mediaFile = mediaIngestService.ingestUrl(url, currentUser.userId());
            redisTemplate.delete(mediaListCacheKey(currentUser.userId()));
            return ApiResponse.success(String.valueOf(mediaFile.getId()));
        } catch (Exception exception) {
            throw new DependencyUnavailableException("链接下载或存储服务暂时不可用", exception);
        }
    }

    @GetMapping("/list")
    public ApiResponse<List<MediaView>> getList(
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        Long userId = currentUser.userId();
        String cacheKey = mediaListCacheKey(userId);

        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                return ApiResponse.success(
                        objectMapper.readValue(json, new TypeReference<List<MediaView>>() { })
                );
            }
        } catch (Exception ignored) {
            // Redis 不可用时回源 MySQL。
        }

        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        query.eq("user_id", userId);
        List<MediaView> media = mediaFileMapper.selectList(query.orderByDesc("id"))
                .stream()
                .map(MediaView::from)
                .toList();

        try {
            redisTemplate.opsForValue().set(
                    cacheKey,
                    objectMapper.writeValueAsString(media),
                    30,
                    TimeUnit.MINUTES
            );
        } catch (Exception ignored) {
            // 缓存写入失败不影响查询结果。
        }
        return ApiResponse.success(media);
    }

    @GetMapping("/{mediaId}/access-url")
    public ApiResponse<MediaAccessView> accessUrl(
            @PathVariable Long mediaId,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        MediaFile mediaFile = mediaService.requireOwnedMedia(mediaId, currentUser.userId());
        return ApiResponse.success(new MediaAccessView(
                mediaService.readableSource(mediaFile.getFilePath()), null));
    }

    @DeleteMapping("/delete")
    public ApiResponse<String> delete(
            @RequestParam("id") Long mediaId,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        mediaService.deleteOwnedMedia(mediaId, currentUser.userId());
        redisTemplate.delete(mediaListCacheKey(currentUser.userId()));
        return ApiResponse.success("删除成功");
    }

    @PostMapping("/init-upload")
    public Result<String> initUpload(@RequestParam String filename,
                                     @RequestParam int totalChunks,
                                     @AuthenticationPrincipal AuthenticatedUser currentUser)
            throws Exception {
        return Result.ok(chunkUploadService.initialize(filename, totalChunks, currentUser.userId()));
    }

    @GetMapping("/upload-status")
    public Result<Set<Integer>> uploadStatus(@RequestParam String uploadId,
                                             @AuthenticationPrincipal AuthenticatedUser currentUser) {
        return Result.ok(chunkUploadService.uploadedChunks(uploadId, currentUser.userId()));
    }

    @DeleteMapping("/upload")
    public Result<Void> cancelUpload(@RequestParam String uploadId,
                                     @AuthenticationPrincipal AuthenticatedUser currentUser) {
        chunkUploadService.cancel(uploadId, currentUser.userId());
        return Result.ok();
    }

    @PostMapping("/upload-chunk")
    public Result<Void> uploadChunk(@RequestParam String uploadId,
                                     @RequestParam int chunkIndex,
                                     @RequestParam int totalChunks,
                                     @RequestParam("file") MultipartFile file,
                                     @AuthenticationPrincipal AuthenticatedUser currentUser)
            throws Exception {
        chunkUploadService.uploadChunk(uploadId, chunkIndex, totalChunks, file, currentUser.userId());
        return Result.ok();
    }

    @PostMapping("/complete-upload")
    public Result<MediaSummary> completeUpload(@RequestParam String uploadId,
                                               @AuthenticationPrincipal AuthenticatedUser currentUser)
            throws Exception {
        return Result.ok(MediaSummary.from(chunkUploadService.complete(uploadId, currentUser.userId())));
    }

    @PostMapping("/upload")
    public Result<MediaSummary> upload(@RequestParam("file") MultipartFile file,
                                       @AuthenticationPrincipal AuthenticatedUser currentUser)
            throws Exception {
        return Result.ok(MediaSummary.from(mediaIngestService.ingestFile(file, currentUser.userId())));
    }

    @GetMapping("/playback")
    public Result<String> playback(@RequestParam Long id,
                                   @AuthenticationPrincipal AuthenticatedUser currentUser) {
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, currentUser.userId());
        return Result.ok(mediaService.readableSource(mediaFile.getFilePath()));
    }

    private String mediaListCacheKey(Long userId) {
        return MEDIA_LIST_CACHE_PREFIX + userId;
    }
}
