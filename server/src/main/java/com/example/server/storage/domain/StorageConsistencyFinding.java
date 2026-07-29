package com.example.server.storage.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("storage_consistency_findings")
public class StorageConsistencyFinding {

    @TableId(type = IdType.AUTO)
    private Long id;
    private StorageFindingType findingType;
    private String objectKey;
    private Long storageObjectId;
    private String details;
    private StorageFindingStatus status;
    private LocalDateTime firstDetectedAt;
    private LocalDateTime lastDetectedAt;
}
