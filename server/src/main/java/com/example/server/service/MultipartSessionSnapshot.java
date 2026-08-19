package com.example.server.service;

import com.example.server.storage.domain.MultipartUploadSession;
import com.example.server.storage.domain.MultipartUploadStatus;

import java.time.LocalDateTime;

public record MultipartSessionSnapshot(
        String sessionId,
        String providerUploadId,
        Long userId,
        String contentHash,
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
                session.getContentHash(),
                session.getTemporaryObjectKey(),
                session.getTotalParts(),
                session.getExpiresAt(),
                session.getStatus()
        );
    }
}
