package com.example.server.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 秒传和断点续传的统一检查请求。 */
public record UploadCheckRequest(
        @NotBlank String filename,
        @NotBlank
        @Pattern(regexp = "^[a-f0-9]{32}$", message = "必须是32位小写MD5")
        String fileMd5,
        @Min(value = 1, message = "必须大于0")
        int totalChunks,
        String uploadId
) {
}
