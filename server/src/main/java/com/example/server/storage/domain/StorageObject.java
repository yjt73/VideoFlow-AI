package com.example.server.storage.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("storage_objects")
public class StorageObject {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String contentHash;
    private String bucketName;
    private String objectKey;
    private Long sizeBytes;
    private String contentType;
    private Integer referenceCount;
    private StorageObjectStatus status;
    private Integer deleteAttempts;
    private LocalDateTime nextDeleteAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
