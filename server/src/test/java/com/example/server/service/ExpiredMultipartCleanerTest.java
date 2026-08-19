package com.example.server.service;

import com.example.server.api.DependencyUnavailableException;
import com.example.server.storage.domain.MultipartUploadSession;
import com.example.server.storage.domain.MultipartUploadStatus;
import com.example.server.storage.persistence.MultipartUploadSessionMapper;
import com.example.server.service.S3MultipartGateway;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExpiredMultipartCleanerTest {

    private final MultipartUploadSessionMapper sessionMapper =
            mock(MultipartUploadSessionMapper.class);
    private final S3MultipartGateway gateway = mock(S3MultipartGateway.class);
    private final ExpiredMultipartCleaner cleaner =
            new ExpiredMultipartCleaner(sessionMapper, gateway);

    @Test
    void abortsAndMarksExpiredInitiatedSessions() {
        MultipartUploadSession session = expiredSession();
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));

        cleaner.cleanExpiredSessions();

        verify(gateway).abort(session.getTemporaryObjectKey(), session.getProviderUploadId());
        verify(sessionMapper).markExpired("session-1");
    }

    @Test
    void leavesSessionForNextScanWhenAbortTemporarilyFails() {
        MultipartUploadSession session = expiredSession();
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        doThrow(new DependencyUnavailableException("unavailable"))
                .when(gateway)
                .abort(session.getTemporaryObjectKey(), session.getProviderUploadId());

        cleaner.cleanExpiredSessions();

        verify(sessionMapper, never()).markExpired("session-1");
    }

    private MultipartUploadSession expiredSession() {
        MultipartUploadSession session = new MultipartUploadSession();
        session.setSessionId("session-1");
        session.setProviderUploadId("provider-1");
        session.setTemporaryObjectKey("temporary/3/session-1/course.mp4");
        session.setStatus(MultipartUploadStatus.INITIATED);
        session.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        return session;
    }
}
