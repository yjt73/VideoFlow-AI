package com.example.server.service;

import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ModelRawResponseService {

    private static final Logger log = LoggerFactory.getLogger(ModelRawResponseService.class);

    public void save(
            String taskId,
            Long mediaId,
            String apiName,
            String stage,
            String modelVersion,
            String prompt,
            String response,
            String parseStatus,
            String errorMessage
    ) {
        log.debug("model_response_recorded taskId={} mediaId={} api={} stage={} status={} responseLength={}",
                taskId, mediaId, apiName, stage, parseStatus,
                response == null ? 0 : response.length());
    }
}
