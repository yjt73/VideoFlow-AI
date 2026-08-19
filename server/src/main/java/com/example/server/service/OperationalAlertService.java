package com.example.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OperationalAlertService {

    private static final Logger log = LoggerFactory.getLogger(OperationalAlertService.class);

    public void warn(String event, String message) {
        log.warn("operation_alert event={} message={}", event, message);
    }

    public void warn(String event, Long userId, Long mediaId, String message) {
        log.warn("operation_alert event={} userId={} mediaId={} message={}", event, userId, mediaId, message);
    }
}
