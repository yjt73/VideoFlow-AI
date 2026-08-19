package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.storage.domain.MultipartUploadSession;
import com.example.server.storage.domain.MultipartUploadStatus;
import com.example.server.storage.persistence.MultipartUploadSessionMapper;
import com.example.server.service.S3MultipartGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class ExpiredMultipartCleaner {

    private static final Logger log = LoggerFactory.getLogger(ExpiredMultipartCleaner.class);
    private static final int BATCH_SIZE = 50;

    private final MultipartUploadSessionMapper sessionMapper;
    private final S3MultipartGateway gateway;

    public ExpiredMultipartCleaner(
            MultipartUploadSessionMapper sessionMapper,
            S3MultipartGateway gateway
    ) {
        this.sessionMapper = sessionMapper;
        this.gateway = gateway;
    }

    @Scheduled(fixedDelayString = "${storage.multipart.cleanup-delay-ms:3600000}")
    public void cleanExpiredSessions() {
        List<MultipartUploadSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<MultipartUploadSession>()
                        .eq(MultipartUploadSession::getStatus, MultipartUploadStatus.INITIATED)
                        .le(MultipartUploadSession::getExpiresAt, LocalDateTime.now())
                        .orderByAsc(MultipartUploadSession::getExpiresAt)
                        .last("LIMIT " + BATCH_SIZE)
        );
        for (MultipartUploadSession session : sessions) {
            try {
                gateway.abort(
                        session.getTemporaryObjectKey(),
                        session.getProviderUploadId()
                );
                sessionMapper.markExpired(session.getSessionId());
            } catch (RuntimeException exception) {
                log.warn(
                        "event=multipart_expiry_cleanup_failed sessionId={} error={}",
                        session.getSessionId(),
                        exception.getMessage()
                );
            }
        }
    }
}
