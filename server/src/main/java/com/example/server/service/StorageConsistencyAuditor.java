package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.storage.domain.StorageConsistencyFinding;
import com.example.server.storage.domain.StorageFindingStatus;
import com.example.server.storage.domain.StorageFindingType;
import com.example.server.storage.domain.StorageObject;
import com.example.server.storage.domain.StorageObjectStatus;
import com.example.server.storage.persistence.StorageConsistencyFindingMapper;
import com.example.server.storage.persistence.StorageObjectMapper;
import com.example.server.service.S3MultipartGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class StorageConsistencyAuditor {

    private static final Logger log = LoggerFactory.getLogger(StorageConsistencyAuditor.class);

    private final StorageObjectMapper storageObjectMapper;
    private final StorageConsistencyFindingMapper findingMapper;
    private final S3MultipartGateway gateway;
    private final int batchSize;

    public StorageConsistencyAuditor(
            StorageObjectMapper storageObjectMapper,
            StorageConsistencyFindingMapper findingMapper,
            S3MultipartGateway gateway,
            @Value("${storage.audit.batch-size:500}") int batchSize
    ) {
        this.storageObjectMapper = storageObjectMapper;
        this.findingMapper = findingMapper;
        this.gateway = gateway;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${storage.audit.delay-ms:86400000}")
    public void audit() {
        auditDatabaseObjects();
        auditStorageKeys();
    }

    private void auditDatabaseObjects() {
        List<StorageObject> objects = storageObjectMapper.selectList(
                new LambdaQueryWrapper<StorageObject>()
                        .eq(StorageObject::getStatus, StorageObjectStatus.ACTIVE)
                        .orderByAsc(StorageObject::getId)
                        .last("LIMIT " + batchSize)
        );
        for (StorageObject object : objects) {
            if (!gateway.exists(object.getObjectKey())) {
                recordFinding(
                        StorageFindingType.DATABASE_OBJECT_MISSING,
                        object.getObjectKey(),
                        object.getId(),
                        "数据库对象记录存在，但对象存储中未找到文件"
                );
            }
        }
    }

    private void auditStorageKeys() {
        gateway.listKeys("media/").stream()
                .limit(batchSize)
                .forEach(objectKey -> {
                    StorageObject object = storageObjectMapper.selectOne(
                            new LambdaQueryWrapper<StorageObject>()
                                    .eq(StorageObject::getObjectKey, objectKey)
                                    .last("LIMIT 1")
                    );
                    if (object == null) {
                        recordFinding(
                                StorageFindingType.ORPHAN_OBJECT,
                                objectKey,
                                null,
                                "对象存储文件没有对应的数据库记录"
                        );
                    }
                });
    }

    private void recordFinding(
            StorageFindingType type,
            String objectKey,
            Long storageObjectId,
            String details
    ) {
        LocalDateTime now = LocalDateTime.now();
        StorageConsistencyFinding existing = findingMapper.selectOne(
                new LambdaQueryWrapper<StorageConsistencyFinding>()
                        .eq(StorageConsistencyFinding::getFindingType, type)
                        .eq(StorageConsistencyFinding::getObjectKey, objectKey)
                        .last("LIMIT 1")
        );
        if (existing == null) {
            StorageConsistencyFinding finding = new StorageConsistencyFinding();
            finding.setFindingType(type);
            finding.setObjectKey(objectKey);
            finding.setStorageObjectId(storageObjectId);
            finding.setDetails(details);
            finding.setStatus(StorageFindingStatus.OPEN);
            finding.setFirstDetectedAt(now);
            finding.setLastDetectedAt(now);
            findingMapper.insert(finding);
        } else {
            existing.setStorageObjectId(storageObjectId);
            existing.setDetails(details);
            if (existing.getStatus() != StorageFindingStatus.IGNORED) {
                existing.setStatus(StorageFindingStatus.OPEN);
            }
            existing.setLastDetectedAt(now);
            findingMapper.updateById(existing);
        }
        log.warn(
                "event=storage_consistency_finding type={} objectKey={} storageObjectId={}",
                type,
                objectKey,
                storageObjectId
        );
    }
}
