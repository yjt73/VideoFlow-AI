package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.api.ApiResponse;
import com.example.server.api.BusinessException;
import com.example.server.api.DependencyUnavailableException;
import com.example.server.api.ErrorCode;
import com.example.server.auth.AuthenticatedUser;
import com.example.server.dto.UploadCheckRequest;
import com.example.server.dto.UploadCheckResponse;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.media.api.MediaView;
import com.example.server.service.MediaService;
import com.example.server.storage.api.MediaAccessView;
import com.example.server.storage.service.StorageAccessService;
import com.example.server.storage.service.StorageReferenceDeletionService;
import com.example.server.storage.service.ServerSideObjectIngestionService;
import com.example.server.utils.YtDlpUtils; //确保导入这个
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 对外提供媒体上传、查询和删除接口。 */
@RestController
@RequestMapping("/media")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class MediaController {

    @Autowired(required = false)
    private MediaFileMapper mediaFileMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    //Jackson 提供的 Java 对象和 JSON 之间的转换工具
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private YtDlpUtils ytDlpUtils;

    @Autowired
    private MediaService mediaService;

    @Autowired
    private StorageAccessService storageAccessService;

    @Autowired
    private StorageReferenceDeletionService referenceDeletionService;

    @Autowired
    private ServerSideObjectIngestionService ingestionService;

    @PostMapping("/init-upload")
    /** 创建分片上传任务并返回 uploadId。 */
    /*做了两件事
    1.为redis中注入key为upload:chunked:{uploadId}的键值对
    2.创建路径为/tmp/dovideo-chunks/550e8400-e29b-41d4-a716-446655440000的文件夹*/
    public ApiResponse<String> initUpload(@RequestParam String filename,
                                          @RequestParam String fileMd5,
                                          @RequestParam int totalChunks,
                                          @AuthenticationPrincipal AuthenticatedUser currentUser) throws Exception {
        return ApiResponse.success(mediaService.initChunkedUpload(
                filename,
                fileMd5,
                totalChunks,
                currentUser.userId()
        ));
    }

    /** 统一检查秒传、已有上传会话和新上传任务。 */
    @PostMapping("/check-upload")
    public ApiResponse<UploadCheckResponse> checkUpload(
            @Valid @RequestBody UploadCheckRequest request,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ApiResponse.success(mediaService.checkUpload(request, currentUser.userId()));
    }

    @GetMapping("/upload-status")
    /** 返回指定上传任务已经完成的分片编号。 */
    public ApiResponse<Set<Integer>> uploadStatus(
            @RequestParam String uploadId,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ApiResponse.success(mediaService.getUploadedChunks(uploadId, currentUser.userId()));
    }

    @PostMapping("/upload-chunk")
    /** 接收并保存一个由前端切出的文件分片。 */
    public ApiResponse<String> uploadChunk(@RequestParam String uploadId,
                                           @RequestParam int chunkIndex,
                                           @RequestParam int totalChunks,
                                           @RequestParam String chunkMd5,
                                           @RequestParam("file") MultipartFile file,
                                           @AuthenticationPrincipal AuthenticatedUser currentUser) throws Exception {
        mediaService.uploadChunk(
                uploadId,
                chunkIndex,
                totalChunks,
                chunkMd5,
                file,
                currentUser.userId()
        );
        return ApiResponse.success("Chunk uploaded");
    }

    @PostMapping("/complete-upload")
    /** 合并全部分片，完成对象存储和数据库持久化。 */
    public ApiResponse<MediaFile> completeUpload(
            @RequestParam String uploadId,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) throws Exception {
        return ApiResponse.success(mediaService.completeChunkedUpload(uploadId, currentUser.userId()));
    }

    /** 取消未完成的上传会话并清理临时分片。 */
    @DeleteMapping("/upload")
    public ApiResponse<String> cancelUpload(
            @RequestParam String uploadId,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        mediaService.cancelUpload(uploadId, currentUser.userId());
        return ApiResponse.success("上传任务已取消");
    }

    @PostMapping("/upload-url")
    /** 下载外部视频链接并将文件转存到对象存储。 */
    public ApiResponse<String> uploadUrl(@RequestParam("url") String url,
                                         @AuthenticationPrincipal AuthenticatedUser currentUser) {
        if (url == null || url.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "URL 不能为空");
        }
        if (mediaFileMapper == null) {
            throw new DependencyUnavailableException("数据库尚未就绪");
        }
        File tempFile = null;
        try {
            System.out.println("Received upload url: " + url);

            tempFile = ytDlpUtils.downloadVideo(url);

            String contentType = java.nio.file.Files.probeContentType(tempFile.toPath());
            ingestionService.ingest(
                    tempFile,
                    "WEB_" + tempFile.getName(),
                    contentType == null ? "video/mp4" : contentType,
                    currentUser.userId()
            );

            String cacheKey = "media:list:user:" + currentUser.userId();
            redisTemplate.delete(cacheKey);
            System.out.println("Cache cleared: " + cacheKey);

            return ApiResponse.success("Upload success");

        } catch (Exception e) {
            throw new DependencyUnavailableException("链接下载或存储服务暂时不可用", e);
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    @GetMapping("/list")
    /** 查询当前用户可见的媒体文件列表。 */
    public ApiResponse<List<MediaView>> getList(@AuthenticationPrincipal AuthenticatedUser currentUser) {
        Long userId = currentUser.userId();
        String cacheKey = "media:list:user:" + userId;

        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                System.out.println("命中 Redis 缓存，直接返回！");
                return ApiResponse.success(
                        objectMapper.readValue(json, new TypeReference<List<MediaView>>() {})
                );
            }
        } catch (Exception e) {
            System.err.println("Redis 读取失败: " + e.getMessage());
        }

        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        query.eq("user_id", userId);
        List<MediaView> list = mediaFileMapper.selectList(query.orderByDesc("id"))
                .stream()
                .map(MediaView::from)
                .toList();

        try {
            String jsonToWrite = objectMapper.writeValueAsString(list);
            redisTemplate.opsForValue().set(cacheKey, jsonToWrite, 30, TimeUnit.MINUTES);
            System.out.println("已写入 Redis 缓存");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return ApiResponse.success(list);
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

    //删除接口
    @DeleteMapping("/delete")
    /** 删除媒体数据库记录及对象存储中的对应文件。 */
    public ApiResponse<String> delete(@RequestParam("id") Long id,
                                      @AuthenticationPrincipal AuthenticatedUser currentUser) {

        referenceDeletionService.deleteOwnedMedia(id, currentUser.userId());
        String cacheKey = "media:list:user:" + currentUser.userId();
        redisTemplate.delete(cacheKey);

        return ApiResponse.success("删除成功");
    }
}
