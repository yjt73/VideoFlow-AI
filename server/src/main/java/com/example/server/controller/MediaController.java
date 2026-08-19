package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.api.ApiResponse;
import com.example.server.api.BusinessException;
import com.example.server.api.DependencyUnavailableException;
import com.example.server.api.ErrorCode;
import com.example.server.auth.AuthenticatedUser;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.media.api.MediaView;
import com.example.server.storage.api.MediaAccessView;
import com.example.server.storage.service.ServerSideObjectIngestionService;
import com.example.server.storage.service.StorageAccessService;
import com.example.server.storage.service.StorageReferenceDeletionService;
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
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
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
    private final StorageAccessService storageAccessService;
    private final StorageReferenceDeletionService referenceDeletionService;
    private final ServerSideObjectIngestionService ingestionService;

    public MediaController(
            MediaFileMapper mediaFileMapper,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            YtDlpUtils ytDlpUtils,
            StorageAccessService storageAccessService,
            StorageReferenceDeletionService referenceDeletionService,
            ServerSideObjectIngestionService ingestionService
    ) {
        this.mediaFileMapper = mediaFileMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ytDlpUtils = ytDlpUtils;
        this.storageAccessService = storageAccessService;
        this.referenceDeletionService = referenceDeletionService;
        this.ingestionService = ingestionService;
    }

    @PostMapping("/upload-url")
    public ApiResponse<String> uploadUrl(
            @RequestParam("url") String url,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        if (url == null || url.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "URL 不能为空");
        }

        File tempFile = null;
        try {
            tempFile = ytDlpUtils.downloadVideo(url);
            String contentType = Files.probeContentType(tempFile.toPath());
            ingestionService.ingest(
                    tempFile,
                    "WEB_" + tempFile.getName(),
                    contentType == null ? "video/mp4" : contentType,
                    currentUser.userId()
            );
            redisTemplate.delete(mediaListCacheKey(currentUser.userId()));
            return ApiResponse.success("Upload success");
        } catch (Exception exception) {
            throw new DependencyUnavailableException("链接下载或存储服务暂时不可用", exception);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile.toPath());
                } catch (Exception ignored) {
                    // 临时文件会由操作系统临时目录的清理策略兜底。
                }
            }
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
        return ApiResponse.success(
                storageAccessService.accessUrl(mediaId, currentUser.userId())
        );
    }

    @DeleteMapping("/delete")
    public ApiResponse<String> delete(
            @RequestParam("id") Long mediaId,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        referenceDeletionService.deleteOwnedMedia(mediaId, currentUser.userId());
        redisTemplate.delete(mediaListCacheKey(currentUser.userId()));
        return ApiResponse.success("删除成功");
    }

    private String mediaListCacheKey(Long userId) {
        return MEDIA_LIST_CACHE_PREFIX + userId;
    }
}
