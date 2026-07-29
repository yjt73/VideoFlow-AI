package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.api.BusinessException;
import com.example.server.api.DependencyUnavailableException;
import com.example.server.api.ErrorCode;
import com.example.server.dto.UploadCheckRequest;
import com.example.server.dto.UploadCheckResponse;
import com.example.server.dto.UploadState;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.storage.service.ServerSideObjectIngestionService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class MediaService {

    //注入数据库操作接口 (MyBatis-Plus 自动代理)
    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ServerSideObjectIngestionService ingestionService;

    @Autowired
    private RedissonClient redissonClient;

    private final String UPLOAD_DIR = "D:/Project/MediaApp/uploads/";
    private static final String CHUNK_UPLOAD_KEY_PREFIX = "upload:chunked:";
    private static final String MEDIA_MD5_KEY_PREFIX = "media:md5:";
    private static final Path CHUNK_UPLOAD_DIR = Path.of(System.getProperty("java.io.tmpdir"), "dovideo-chunks");

    public MediaService() {
        File dir = new File(UPLOAD_DIR);
        if (!dir.exists()) dir.mkdirs();
    }

    public UploadCheckResponse checkUpload(UploadCheckRequest request, Long userId) {
        validateFileMd5(request.fileMd5());
        MediaFile existing = findOwnedMedia(userId, request.fileMd5());
        if (existing != null) {
            return new UploadCheckResponse(
                    UploadState.COMPLETED,
                    null,
                    Set.of(),
                    true,
                    existing
            );
        }

        if (request.uploadId() != null && !request.uploadId().isBlank()) {
            Map<Object, Object> metadata = requireUpload(request.uploadId());
            requireOwner(metadata, userId);
            if (!request.fileMd5().equals(String.valueOf(metadata.get("fileMd5")))
                    || request.totalChunks() != Integer.parseInt(String.valueOf(metadata.get("totalChunks")))) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "上传任务与当前文件不匹配");
            }
            return new UploadCheckResponse(
                    UploadState.UPLOADING,
                    request.uploadId(),
                    getUploadedChunks(request.uploadId(), userId),
                    false,
                    null
            );
        }

        String uploadId = initChunkedUpload(
                request.filename(),
                request.fileMd5(),
                request.totalChunks(),
                userId
        );
        return new UploadCheckResponse(UploadState.NEW, uploadId, Set.of(), false, null);
    }

    public String initChunkedUpload(String filename, String fileMd5, int totalChunks, Long userId) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename is required");
        }
        validateFileMd5(fileMd5);
        if (totalChunks <= 0) {
            throw new IllegalArgumentException("totalChunks must be greater than 0");
        }

        String uploadId = UUID.randomUUID().toString();
        String redisKey = CHUNK_UPLOAD_KEY_PREFIX + uploadId;
        Map<String, String> metadata = new HashMap<>();
        metadata.put("filename", filename);
        metadata.put("fileMd5", fileMd5);
        metadata.put("totalChunks", String.valueOf(totalChunks));
        metadata.put("status", "UPLOADING");
        if (userId != null) {
            metadata.put("userId", String.valueOf(userId));
        }
        redisTemplate.opsForHash().putAll(redisKey, metadata);
        redisTemplate.expire(redisKey, 1, TimeUnit.DAYS);
        try {
            Files.createDirectories(uploadDirectory(uploadId));
        } catch (IOException e) {
            redisTemplate.delete(redisKey);
            throw new DependencyUnavailableException("无法创建上传临时目录", e);
        }
        return uploadId;
    }

    public Set<Integer> getUploadedChunks(String uploadId, Long userId) {
        requireOwnedUpload(uploadId, userId);
        Set<String> members = redisTemplate.opsForSet().members(partsKey(uploadId));
        Set<Integer> result = new TreeSet<>();
        if (members != null) {
            for (String member : members) {
                result.add(Integer.parseInt(member));
            }
        }
        return result;
    }

    public void uploadChunk(
            String uploadId,
            int chunkIndex,
            int totalChunks,
            String chunkMd5,
            MultipartFile chunk,
            Long userId
    ) throws IOException {
        RLock lock = redissonClient.getReadWriteLock(
                "lock:upload:merge:" + uploadId
        ).readLock();
        if (!lock.tryLock()) {
            throw new DependencyUnavailableException("上传任务正在合并，分片稍后重试");
        }
        try {
            uploadChunkUnderLock(
                    uploadId,
                    chunkIndex,
                    totalChunks,
                    chunkMd5,
                    chunk,
                    userId
            );
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void uploadChunkUnderLock(
            String uploadId,
            int chunkIndex,
            int totalChunks,
            String chunkMd5,
            MultipartFile chunk,
            Long userId
    ) throws IOException {
        if (chunk == null || chunk.isEmpty()) {
            throw new IllegalArgumentException("chunk is empty");
        }
        validateFileMd5(chunkMd5);

        Map<Object, Object> metadata = requireOwnedUpload(uploadId, userId);
        if (!"UPLOADING".equals(String.valueOf(metadata.get("status")))) {
            throw new BusinessException(ErrorCode.CONFLICT, "上传任务当前不可接收分片");
        }
        int expectedChunks = Integer.parseInt(String.valueOf(metadata.get("totalChunks")));
        if (totalChunks != expectedChunks || chunkIndex < 0 || chunkIndex >= expectedChunks) {
            throw new IllegalArgumentException("invalid chunk index or totalChunks");
        }

        Path directory = uploadDirectory(uploadId);
        Files.createDirectories(directory);
        Path temporary = directory.resolve("part-" + chunkIndex + ".tmp-" + UUID.randomUUID());
        try {
            MessageDigest digest = md5Digest();
            try (InputStream input = chunk.getInputStream();
                 OutputStream fileOutput = Files.newOutputStream(temporary);
                 DigestOutputStream output = new DigestOutputStream(fileOutput, digest)) {
                input.transferTo(output);
            }
            String actualMd5 = HexFormat.of().formatHex(digest.digest());
            if (!actualMd5.equals(chunkMd5)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "分片完整性校验失败");
            }
            moveReplacingAtomically(temporary, chunkPath(directory, chunkIndex));
        } finally {
            Files.deleteIfExists(temporary);
        }
        redisTemplate.opsForSet().add(partsKey(uploadId), String.valueOf(chunkIndex));
        redisTemplate.expire(CHUNK_UPLOAD_KEY_PREFIX + uploadId, 1, TimeUnit.DAYS);
        redisTemplate.expire(partsKey(uploadId), 1, TimeUnit.DAYS);
    }

    public MediaFile completeChunkedUpload(String uploadId, Long userId) throws Exception {
        requireOwnedUpload(uploadId, userId);
        RLock lock = redissonClient.getReadWriteLock(
                "lock:upload:merge:" + uploadId
        ).writeLock();
        if (!lock.tryLock()) {
            throw new DependencyUnavailableException("上传任务正在合并，请稍后重试");
        }
        try {
            return completeChunkedUploadUnderLock(uploadId, userId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private MediaFile completeChunkedUploadUnderLock(String uploadId, Long userId) throws Exception {
        Map<Object, Object> metadata = requireOwnedUpload(uploadId, userId);
        if ("COMPLETED".equals(String.valueOf(metadata.get("status")))) {
            Object mediaId = metadata.get("mediaId");
            if (mediaId == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "已完成的上传任务缺少媒体记录");
            }
            MediaFile completed = mediaFileMapper.selectById(Long.valueOf(String.valueOf(mediaId)));
            if (completed == null || !userId.equals(completed.getUserId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "媒体记录不存在");
            }
            return completed;
        }

        String filename = String.valueOf(metadata.get("filename"));
        String expectedMd5 = String.valueOf(metadata.get("fileMd5"));
        int totalChunks = Integer.parseInt(String.valueOf(metadata.get("totalChunks")));
        Path directory = uploadDirectory(uploadId);
        Set<Integer> uploadedChunks = getUploadedChunks(uploadId, userId);
        if (uploadedChunks.size() != totalChunks) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分片尚未全部上传");
        }

        redisTemplate.opsForHash().put(CHUNK_UPLOAD_KEY_PREFIX + uploadId, "status", "MERGING");
        Path mergedFile = directory.resolve("merged" + fileSuffix(filename));
        try {
            String actualMd5 = mergeChunksAndCalculateMd5(directory, mergedFile, totalChunks);
            if (!expectedMd5.equals(actualMd5)) {
                redisTemplate.opsForHash().put(
                        CHUNK_UPLOAD_KEY_PREFIX + uploadId,
                        "status",
                        "UPLOADING"
                );
                throw new BusinessException(ErrorCode.BAD_REQUEST, "整文件完整性校验失败");
            }

            try {
                MediaFile mediaFile = ingestionService.ingest(
                        mergedFile.toFile(),
                        filename,
                        "video/mp4",
                        userId
                );
                markUploadCompleted(uploadId, mediaFile.getId());
                invalidateMediaList(userId);
                try {
                    cleanupUploadFiles(uploadId);
                } catch (Exception ignored) {
                    // 数据库已成功入库，临时文件清理由后续维护任务兜底。
                }
                return mediaFile;
            } catch (Exception e) {
                restoreUploadStatus(uploadId);
                throw new DependencyUnavailableException("对象存储暂时不可用", e);
            }
        } catch (BusinessException e) {
            restoreUploadStatus(uploadId);
            throw e;
        } catch (Exception e) {
            restoreUploadStatus(uploadId);
            throw new DependencyUnavailableException("文件合并暂时失败", e);
        } finally {
            Files.deleteIfExists(mergedFile);
        }
    }

    private String mergeChunksAndCalculateMd5(Path directory, Path mergedFile, int totalChunks)
            throws IOException {
        MessageDigest digest = md5Digest();
        try (OutputStream fileOutput = Files.newOutputStream(mergedFile);
             DigestOutputStream digestOutput = new DigestOutputStream(fileOutput, digest);
             BufferedOutputStream output = new BufferedOutputStream(digestOutput)) {
            for (int i = 0; i < totalChunks; i++) {
                Path part = chunkPath(directory, i);
                if (!Files.isRegularFile(part)) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少分片: " + i);
                }
                Files.copy(part, output);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public void cancelUpload(String uploadId, Long userId) {
        uploadDirectory(uploadId);
        RLock lock = redissonClient.getReadWriteLock(
                "lock:upload:merge:" + uploadId
        ).writeLock();
        if (!lock.tryLock()) {
            throw new DependencyUnavailableException("上传任务正在合并，暂时无法取消");
        }
        try {
            Map<Object, Object> metadata = redisTemplate.opsForHash()
                    .entries(CHUNK_UPLOAD_KEY_PREFIX + uploadId);
            if (!metadata.isEmpty()) {
                requireOwner(metadata, userId);
            }
            try {
                cleanupUpload(uploadId);
            } catch (IOException e) {
                throw new DependencyUnavailableException("上传临时文件清理失败", e);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public String calculateMd5(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            return calculateMd5(inputStream);
        }
    }

    public String calculateMd5(File file) throws IOException {
        try (InputStream inputStream = Files.newInputStream(file.toPath())) {
            return calculateMd5(inputStream);
        }
    }

    public void rememberContentHash(Long mediaId, String md5) {
        try {
            redisTemplate.opsForValue().set(MEDIA_MD5_KEY_PREFIX + mediaId, md5);
        } catch (Exception ignored) {
            // content_hash 已持久化到 MySQL，Redis 只作为兼容缓存。
        }
    }

    private String calculateMd5(InputStream inputStream) throws IOException {
        MessageDigest digest = md5Digest();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }

    private Map<Object, Object> requireUpload(String uploadId) {
        uploadDirectory(uploadId);
        Map<Object, Object> metadata = redisTemplate.opsForHash()
                .entries(CHUNK_UPLOAD_KEY_PREFIX + uploadId);
        if (metadata.isEmpty()) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_EXPIRED, "上传任务不存在或已过期");
        }
        return metadata;
    }

    private Map<Object, Object> requireOwnedUpload(String uploadId, Long userId) {
        Map<Object, Object> metadata = requireUpload(uploadId);
        requireOwner(metadata, userId);
        return metadata;
    }

    private void requireOwner(Map<Object, Object> metadata, Long userId) {
        Object ownerId = metadata.get("userId");
        if (ownerId == null || !String.valueOf(userId).equals(String.valueOf(ownerId))) {
            throw new AccessDeniedException("无权访问他人的上传任务");
        }
    }

    private MediaFile findOwnedMedia(Long userId, String contentHash) {
        return mediaFileMapper.selectOne(new QueryWrapper<MediaFile>()
                .eq("user_id", userId)
                .eq("content_hash", contentHash)
                .last("LIMIT 1"));
    }

    private void validateFileMd5(String fileMd5) {
        if (fileMd5 == null || !fileMd5.matches("^[a-f0-9]{32}$")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "fileMd5 必须是32位小写MD5");
        }
    }

    private Path uploadDirectory(String uploadId) {
        try {
            UUID.fromString(uploadId);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid uploadId");
        }
        return CHUNK_UPLOAD_DIR.resolve(uploadId);
    }

    private Path chunkPath(Path directory, int chunkIndex) {
        return directory.resolve("part-" + chunkIndex);
    }

    private void moveReplacingAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("当前文件系统不支持分片原子落盘", exception);
        }
    }

    private String partsKey(String uploadId) {
        return CHUNK_UPLOAD_KEY_PREFIX + uploadId + ":parts";
    }

    private String fileSuffix(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    private void cleanupUpload(String uploadId) throws IOException {
        deleteUploadDirectory(uploadId);
        redisTemplate.delete(List.of(CHUNK_UPLOAD_KEY_PREFIX + uploadId, partsKey(uploadId)));
    }

    private void cleanupUploadFiles(String uploadId) throws IOException {
        deleteUploadDirectory(uploadId);
        redisTemplate.delete(partsKey(uploadId));
    }

    private void deleteUploadDirectory(String uploadId) throws IOException {
        Path directory = uploadDirectory(uploadId);
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new IllegalStateException("failed to clean upload files", e);
                    }
                });
            }
        }
    }

    private void markUploadCompleted(String uploadId, Long mediaId) {
        try {
            String redisKey = CHUNK_UPLOAD_KEY_PREFIX + uploadId;
            redisTemplate.opsForHash().putAll(
                    redisKey,
                    Map.of("status", "COMPLETED", "mediaId", String.valueOf(mediaId))
            );
            redisTemplate.expire(redisKey, 1, TimeUnit.DAYS);
        } catch (Exception ignored) {
            // MySQL 是最终事实来源，Redis 恢复后 check-upload 会命中数据库。
        }
    }

    private void restoreUploadStatus(String uploadId) {
        try {
            redisTemplate.opsForHash().put(
                    CHUNK_UPLOAD_KEY_PREFIX + uploadId,
                    "status",
                    "UPLOADING"
            );
        } catch (Exception ignored) {
            // 依赖恢复后客户端会重新检查会话。
        }
    }

    private void invalidateMediaList(Long userId) {
        try {
            redisTemplate.delete("media:list:user:" + userId);
        } catch (Exception ignored) {
            // 列表缓存可自然过期。
        }
    }

    public String convertVideoToAudio(MultipartFile file) throws IOException, InterruptedException {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setFilename(file.getOriginalFilename());
        mediaFile.setStatus("PROCESSING"); //状态：处理中
        mediaFile.setUploadTime(LocalDateTime.now());
        mediaFile.setFilePath(""); //暂时为空

        //这一步执行后，MySQL 里就会多一行数据
        mediaFileMapper.insert(mediaFile);

        // --- 下面是原有的文件处理逻辑 ---
        String fileId = UUID.randomUUID().toString();
        String inputPath = UPLOAD_DIR + fileId + "_input.mp4";
        String outputPath = UPLOAD_DIR + fileId + "_output.mp3";

        File inputFile = new File(inputPath);
        file.transferTo(inputFile);

        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-i");
        command.add(inputFile.getAbsolutePath());
        command.add("-vn");
        command.add("-acodec");
        command.add("libmp3lame");
        command.add("-q:a");
        command.add("2");
        command.add(new File(outputPath).getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        if (process.waitFor() == 0) {
            inputFile.delete(); // 删掉原视频

            // --- 数据库操作：更新状态为完成 ---
            mediaFile.setStatus("COMPLETED");
            mediaFile.setFilePath(outputPath);
            mediaFileMapper.updateById(mediaFile); // 根据 ID 更新这一行

            return outputPath;
        } else {
            // --- 数据库操作：记录失败 ---
            mediaFile.setStatus("FAILED");
            mediaFileMapper.updateById(mediaFile);
            throw new RuntimeException("FFmpeg 转换失败");
        }
    }
}
