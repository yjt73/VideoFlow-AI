package com.example.server.qa.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VideoQaRequest(
        String conversationId,
        @NotBlank(message = "问题不能为空")
        @Size(max = 1000, message = "问题不能超过 1000 个字符")
        String question
) {
}
