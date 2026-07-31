package com.example.server.qa.service;

import java.util.List;

public record VideoQaToolResult(String toolName, String summary, List<VideoQaCitation> citations) {
}
