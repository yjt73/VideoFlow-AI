package com.example.server.dto;

import com.example.server.entity.MediaFile;

import java.time.LocalDateTime;

public record MediaView(
        Long id,
        String filename,
        String status,
        String contentHash,
        String aiSummary,
        String transcriptText,
        String coverUrl,
        LocalDateTime uploadTime
) {

    public static MediaView from(MediaFile media) {
        return new MediaView(
                media.getId(),
                media.getFilename(),
                media.getStatus(),
                media.getContentHash(),
                media.getAiSummary(),
                media.getTranscriptText(),
                media.getCoverUrl(),
                media.getUploadTime()
        );
    }
}
