package com.example.server.dto;

import java.util.List;

public record VideoQaResponse(String conversationId, String answer, List<VideoQaCitationView> citations) {
}
