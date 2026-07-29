package com.example.server.storage.service;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class StorageObjectKeyFactory {

    private static final String MD5_PATTERN = "[0-9a-fA-F]{32}";
    private static final String SESSION_PATTERN = "[0-9A-Za-z-]{1,64}";

    public String canonicalKey(String contentHash) {
        String normalizedHash = normalizeHash(contentHash);
        return "media/" + normalizedHash.substring(0, 2) + "/" + normalizedHash;
    }

    public String temporaryKey(Long userId, String sessionId, String filename) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (sessionId == null || !sessionId.matches(SESSION_PATTERN)) {
            throw new IllegalArgumentException("invalid sessionId");
        }
        return "temporary/" + userId + "/" + sessionId + "/" + sanitizeFilename(filename);
    }

    private String normalizeHash(String contentHash) {
        if (contentHash == null || !contentHash.matches(MD5_PATTERN)) {
            throw new IllegalArgumentException("contentHash must be a 32-character MD5");
        }
        return contentHash.toLowerCase(Locale.ROOT);
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "file";
        }
        String basename = filename.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1);
        StringBuilder safe = new StringBuilder();
        basename.codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint)
                    || codePoint == '.'
                    || codePoint == '-'
                    || codePoint == '_') {
                safe.appendCodePoint(codePoint);
            } else {
                safe.append('_');
            }
        });
        String result = safe.toString();
        while (result.contains("..")) {
            result = result.replace("..", ".");
        }
        if (result.isBlank() || ".".equals(result)) {
            return "file";
        }
        return result.length() <= 200 ? result : result.substring(result.length() - 200);
    }
}
