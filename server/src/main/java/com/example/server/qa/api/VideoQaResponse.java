package com.example.server.qa.api;

import java.util.List;

public record VideoQaResponse(String conversationId, String answer, List<VideoQaCitationView> citations) {
}
