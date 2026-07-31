package com.example.server.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class StructuredModelOutputParser {

    private final ObjectMapper objectMapper;

    public StructuredModelOutputParser() {
        this(new ObjectMapper());
    }

    public StructuredModelOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> T parse(String response, Class<T> type) {
        try {
            return objectMapper.readValue(extractJson(response), type);
        } catch (Exception exception) {
            throw new StructuredModelOutputException(
                    "Model output is not valid structured JSON",
                    exception
            );
        }
    }

    public String extractJson(String response) {
        if (response == null || response.isBlank()) {
            throw new StructuredModelOutputException(
                    "Model output is empty",
                    null
            );
        }
        String cleaned = response
                .replace("```json", "```")
                .replace("```JSON", "```")
                .trim();
        int fenceStart = cleaned.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = fenceStart + 3;
            int fenceEnd = cleaned.indexOf("```", contentStart);
            if (fenceEnd > contentStart) {
                cleaned = cleaned.substring(contentStart, fenceEnd).trim();
            }
        }
        int objectStart = cleaned.indexOf('{');
        int arrayStart = cleaned.indexOf('[');
        int start;
        if (objectStart < 0) {
            start = arrayStart;
        } else if (arrayStart < 0) {
            start = objectStart;
        } else {
            start = Math.min(objectStart, arrayStart);
        }
        if (start < 0) {
            throw new StructuredModelOutputException(
                    "Model output does not contain JSON",
                    null
            );
        }
        char open = cleaned.charAt(start);
        char close = open == '{' ? '}' : ']';
        int end = cleaned.lastIndexOf(close);
        if (end <= start) {
            throw new StructuredModelOutputException(
                    "Model output JSON is incomplete",
                    null
            );
        }
        return cleaned.substring(start, end + 1);
    }
}
