package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.storage.domain.StorageObject;
import com.example.server.storage.domain.StorageObjectStatus;
import com.example.server.storage.persistence.StorageObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.time.LocalDateTime;
import java.util.List;

@Profile("legacy-persistence")
@Component
public class StorageObjectDeleteWorker {

    private static final int BATCH_SIZE = 50;
    private static final long[] BACKOFF_SECONDS = {60, 300, 1800, 3600, 18000};

    private final StorageObjectMapper storageObjectMapper;
    private final S3MultipartGateway gateway;

    public StorageObjectDeleteWorker(
            StorageObjectMapper storageObjectMapper,
            S3MultipartGateway gateway
    ) {
        this.storageObjectMapper = storageObjectMapper;
        this.gateway = gateway;
    }

    @Scheduled(fixedDelayString = "${storage.delete.scan-delay-ms:60000}")
    public void deletePendingObjects() {
        LocalDateTime now = LocalDateTime.now();
        List<StorageObject> candidates = storageObjectMapper.selectList(
                new LambdaQueryWrapper<StorageObject>()
                        .in(
                                StorageObject::getStatus,
                                StorageObjectStatus.DELETE_PENDING,
                                StorageObjectStatus.DELETE_FAILED
                        )
                        .eq(StorageObject::getReferenceCount, 0)
                        .le(StorageObject::getNextDeleteAt, now)
                        .orderByAsc(StorageObject::getNextDeleteAt)
                        .last("LIMIT " + BATCH_SIZE)
        );
        for (StorageObject object : candidates) {
            deleteOne(object);
        }
    }

    private void deleteOne(StorageObject object) {
        try {
            gateway.delete(object.getObjectKey());
            storageObjectMapper.deleteIfUnreferenced(object.getId());
        } catch (RuntimeException exception) {
            int attempts = object.getDeleteAttempts() == null ? 0 : object.getDeleteAttempts();
            long delaySeconds = BACKOFF_SECONDS[
                    Math.min(attempts, BACKOFF_SECONDS.length - 1)
                    ];
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            storageObjectMapper.recordDeleteFailure(
                    object.getId(),
                    truncate(message),
                    LocalDateTime.now().plusSeconds(delaySeconds)
            );
        }
    }

    private String truncate(String message) {
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
