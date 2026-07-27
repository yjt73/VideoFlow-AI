package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.auth.AuthenticatedUser;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.MediaService;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.YtDlpUtils; //确保导入这个
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
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
    private MinioUtils minioUtils;

    @Autowired
    private YtDlpUtils ytDlpUtils;

    @Autowired
    private MediaService mediaService;

    @PostMapping("/init-upload")
    /** 创建分片上传任务并返回 uploadId。 */
    /*做了两件事
    1.为redis中注入key为upload:chunked:{uploadId}的键值对
    2.创建路径为/tmp/dovideo-chunks/550e8400-e29b-41d4-a716-446655440000的文件夹*/
    public ResponseEntity<String> initUpload(@RequestParam String filename,
                                             @RequestParam int totalChunks,
                                             @AuthenticationPrincipal AuthenticatedUser currentUser) {
        try {
            return ResponseEntity.ok(mediaService.initChunkedUpload(
                    filename,
                    totalChunks,
                    currentUser.userId()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Failed to initialize upload");
        }
    }

    @GetMapping("/upload-status")
    /** 返回指定上传任务已经完成的分片编号。 */
    public ResponseEntity<?> uploadStatus(@RequestParam String uploadId,
                                          @AuthenticationPrincipal AuthenticatedUser currentUser) {
        try {
            Set<Integer> uploadedChunks = mediaService.getUploadedChunks(uploadId, currentUser.userId());
            return ResponseEntity.ok(uploadedChunks);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/upload-chunk")
    /** 接收并保存一个由前端切出的文件分片。 */
    public ResponseEntity<String> uploadChunk(@RequestParam String uploadId,
                                              @RequestParam int chunkIndex,
                                              @RequestParam int totalChunks,
                                              @RequestParam("file") MultipartFile file,
                                              @AuthenticationPrincipal AuthenticatedUser currentUser) {
        try {
            mediaService.uploadChunk(uploadId, chunkIndex, totalChunks, file, currentUser.userId());
            return ResponseEntity.ok("Chunk uploaded");
        } catch (AccessDeniedException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Chunk upload failed");
        }
    }

    @PostMapping("/complete-upload")
    /** 合并全部分片，完成对象存储和数据库持久化。 */
    public ResponseEntity<String> completeUpload(@RequestParam String uploadId,
                                                 @AuthenticationPrincipal AuthenticatedUser currentUser) {
        try {
            mediaService.completeChunkedUpload(uploadId, currentUser.userId());
            return ResponseEntity.ok("Upload success");
        } catch (AccessDeniedException e) {
            throw e;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Upload merge failed");
        }
    }


    @PostMapping("/upload")
    /** 兼容小文件场景的一次性完整文件上传接口。 */
    public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file,
                                         @AuthenticationPrincipal AuthenticatedUser currentUser) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body("Upload failed: file is empty");
        }
        if (mediaFileMapper == null) {
            return ResponseEntity.status(500).body("Upload failed: database not ready");
        }
        try {
            String md5 = mediaService.calculateMd5(file);
            System.out.println("Uploading to MinIO...");
            String fileUrl = minioUtils.uploadFile(file);
            System.out.println("MinIO upload success, url: " + fileUrl);

            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename(file.getOriginalFilename());
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");
            mediaFile.setUploadTime(LocalDateTime.now());

            mediaFile.setUserId(currentUser.userId());

            mediaFileMapper.insert(mediaFile);
            mediaService.rememberContentHash(mediaFile.getId(), md5);

            String cacheKey = "media:list:user:" + currentUser.userId();
            redisTemplate.delete(cacheKey);
            System.out.println("Cache cleared: " + cacheKey);

            return ResponseEntity.ok("Upload success");

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Upload failed: " + e.getMessage());
        }
    }

    @PostMapping("/upload-url")
    /** 下载外部视频链接并将文件转存到对象存储。 */
    public org.springframework.http.ResponseEntity<String> uploadUrl(@RequestParam("url") String url,
                                                                     @AuthenticationPrincipal AuthenticatedUser currentUser) {
        File tempFile = null;
        try {
            if (url == null || url.isBlank()) {
                return org.springframework.http.ResponseEntity.badRequest().body("Upload failed: url is empty");
            }
            if (mediaFileMapper == null) {
                return org.springframework.http.ResponseEntity.status(500).body("Upload failed: database not ready");
            }
            System.out.println("Received upload url: " + url);

            tempFile = ytDlpUtils.downloadVideo(url);

            String md5 = mediaService.calculateMd5(tempFile);
            String fileUrl = minioUtils.uploadLocalFile(tempFile);

            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename("WEB_" + tempFile.getName());
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");
            mediaFile.setUploadTime(LocalDateTime.now());

            mediaFile.setUserId(currentUser.userId());

            mediaFileMapper.insert(mediaFile);
            mediaService.rememberContentHash(mediaFile.getId(), md5);

            String cacheKey = "media:list:user:" + currentUser.userId();
            redisTemplate.delete(cacheKey);
            System.out.println("Cache cleared: " + cacheKey);

            return org.springframework.http.ResponseEntity.ok("Upload success");

        } catch (Exception e) {
            e.printStackTrace();
            return org.springframework.http.ResponseEntity.status(500).body("Upload failed: " + e.getMessage());
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    @GetMapping("/list")
    /** 查询当前用户可见的媒体文件列表。 */
    public List<MediaFile> getList(@AuthenticationPrincipal AuthenticatedUser currentUser) {
        Long userId = currentUser.userId();
        String cacheKey = "media:list:user:" + userId;

        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                System.out.println("命中 Redis 缓存，直接返回！");
                return objectMapper.readValue(json, new TypeReference<List<MediaFile>>(){});
            }
        } catch (Exception e) {
            System.err.println("Redis 读取失败: " + e.getMessage());
        }

        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        query.eq("user_id", userId);
        List<MediaFile> list = mediaFileMapper.selectList(query.orderByDesc("id"));

        try {
            String jsonToWrite = objectMapper.writeValueAsString(list);
            redisTemplate.opsForValue().set(cacheKey, jsonToWrite, 30, TimeUnit.MINUTES);
            System.out.println("已写入 Redis 缓存");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return list;
    }

    //删除接口
    @DeleteMapping("/delete")
    /** 删除媒体数据库记录及对象存储中的对应文件。 */
    public ResponseEntity<String> delete(@RequestParam("id") Long id,
                                         @AuthenticationPrincipal AuthenticatedUser currentUser) {

        MediaFile media = mediaFileMapper.selectById(id);
        if (media == null) {
            return ResponseEntity.notFound().build();
        }

        if (!Objects.equals(media.getUserId(), currentUser.userId())) {
            return ResponseEntity.status(403).body("无权删除他人的文件");
        }

        if (media.getFilePath() != null && media.getFilePath().startsWith("http")) {
            minioUtils.removeFile(media.getFilePath());
        }

        mediaFileMapper.deleteById(id);

        if (media.getUserId() != null) {
            String cacheKey = "media:list:user:" + media.getUserId();
            redisTemplate.delete(cacheKey);
            System.out.println("缓存已清除: " + cacheKey);
        }

        return ResponseEntity.ok("删除成功");
    }
}
