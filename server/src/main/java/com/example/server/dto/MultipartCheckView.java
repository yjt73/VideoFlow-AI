package com.example.server.dto;

import com.example.server.dto.UploadState;
import com.example.server.entity.MediaFile;

import java.time.LocalDateTime;
import java.util.List;

public record MultipartCheckView(
        UploadState state,
        String sessionId,
        MediaFile media,
        long partSize,
        int totalParts,
        LocalDateTime expiresAt,
        List<Integer> completedParts
) {
}
