package com.example.server.dto;

import com.example.server.entity.MediaFile;

import java.util.Set;

/** 上传检查结果，统一表达新建、续传和秒传。 */
public record UploadCheckResponse(
        UploadState state,
        String uploadId,
        Set<Integer> uploadedChunks,
        boolean deduplicated,
        MediaFile media
) {
}
