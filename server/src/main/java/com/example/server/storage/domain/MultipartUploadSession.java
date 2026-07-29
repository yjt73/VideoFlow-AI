package com.example.server.storage.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("multipart_upload_sessions")
public class MultipartUploadSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String providerUploadId;
    private Long userId;
    private String filename;
    private String contentHash;
    private String contentType;
    private Long sizeBytes;
    private String bucketName;
    private String temporaryObjectKey;
    private Integer totalParts;
    private Long partSize;
    private MultipartUploadStatus status;
    private Long mediaId;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
