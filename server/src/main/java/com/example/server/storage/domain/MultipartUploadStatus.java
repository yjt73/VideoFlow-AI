package com.example.server.storage.domain;

public enum MultipartUploadStatus {
    INITIATED,
    COMPLETING,
    COMPLETED,
    ABORTED,
    EXPIRED,
    FAILED
}
