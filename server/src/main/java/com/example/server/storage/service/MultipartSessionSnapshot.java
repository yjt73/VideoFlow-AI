package com.example.server.storage.service;

import com.example.server.storage.domain.MultipartUploadSession;
import com.example.server.storage.domain.MultipartUploadStatus;

import java.time.LocalDateTime;

public record MultipartSessionSnapshot(
        String sessionId,
        String providerUploadId,
        Long userId,
        String temporaryObjectKey,
        int totalParts,
        LocalDateTime expiresAt,
        MultipartUploadStatus status
) {

    public static MultipartSessionSnapshot from(MultipartUploadSession session) {
        return new MultipartSessionSnapshot(
                session.getSessionId(),
                session.getProviderUploadId(),
                session.getUserId(),
                session.getTemporaryObjectKey(),
                session.getTotalParts(),
                session.getExpiresAt(),
                session.getStatus()
        );
    }
}
