package com.example.server.service;

import java.util.List;

public record VideoQaToolResult(String toolName, String summary, List<VideoQaCitation> citations) {
}
