package com.example.server.storage.api;

import com.example.server.dto.UploadState;
import com.example.server.entity.MediaFile;

import java.time.LocalDateTime;

public record MultipartCheckView(
        UploadState state,
        String sessionId,
        MediaFile media,
        long partSize,
        int totalParts,
        LocalDateTime expiresAt
) {
}
