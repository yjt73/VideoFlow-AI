package com.example.server.service;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class QdrantVectorStore {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String collection;
    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    @Autowired
    public QdrantVectorStore(
            @Value("${vector.qdrant.enabled:true}") boolean enabled,
            @Value("${vector.qdrant.url:http://localhost:6333}") String baseUrl,
            @Value("${vector.qdrant.api-key:}") String apiKey,
            @Value("${vector.qdrant.collection:video_chunks}") String collection,
            ObjectMapper objectMapper) {
        this(enabled, baseUrl, apiKey, collection,
                new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .readTimeout(Duration.ofSeconds(10))
                        .build(),
                objectMapper);
    }

    QdrantVectorStore(boolean enabled,
                      String baseUrl,
                      String apiKey,
                      String collection,
                      OkHttpClient client,
                      ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.baseUrl = trimSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.collection = collection;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public void upsert(Long mediaId, List<VideoChunk> chunks) {
        if (!enabled || chunks == null || chunks.isEmpty()) return;
        List<Map<String, Object>> points = new ArrayList<>();
        int dimension = 0;
        for (VideoChunk chunk : chunks) {
            if (chunk.embedding() == null || chunk.embedding().isEmpty()) continue;
            dimension = chunk.embedding().size();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", pointId(mediaId, chunk.startTime(), chunk.endTime()));
            point.put("vector", chunk.embedding());
            point.put("payload", payload(mediaId, chunk));
            points.add(point);
        }
        if (points.isEmpty()) return;
        try {
            ensureCollection(dimension);
            Map<String, Object> body = Map.of("points", points);
            execute("PUT", "/collections/" + collection + "/points?wait=true", body);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Qdrant upsert failed", exception);
        }
    }

    public List<VectorHit> search(Long mediaId, List<Double> queryEmbedding, int limit) {
        if (!enabled || queryEmbedding == null || queryEmbedding.isEmpty() || limit <= 0) {
            return List.of();
        }
        try {
            ensureCollection(queryEmbedding.size());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", queryEmbedding);
            body.put("limit", limit);
            body.put("with_payload", true);
            body.put("filter", Map.of("must", List.of(
                    Map.of("key", "mediaId", "match", Map.of("value", mediaId)))));
            JsonNode root = execute("POST", "/collections/" + collection + "/points/search", body);
            List<VectorHit> hits = new ArrayList<>();
            for (JsonNode item : root.path("result")) {
                JsonNode payload = item.path("payload");
                hits.add(new VectorHit(
                        payload.path("startMs").asLong(),
                        payload.path("endMs").asLong(),
                        payload.path("source").asText("VIDEO_CHUNK"),
                        payload.path("snippet").asText(""),
                        payload.path("transcript").asText(""),
                        objectMapper.convertValue(payload.path("ocrTexts"), objectMapper.getTypeFactory()
                                .constructCollectionType(List.class, String.class)),
                        item.path("score").asDouble()));
            }
            return hits;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Qdrant search failed", exception);
        }
    }

    public void deleteMedia(Long mediaId) {
        if (!enabled) return;
        Map<String, Object> body = Map.of("filter", Map.of("must", List.of(
                Map.of("key", "mediaId", "match", Map.of("value", mediaId)))));
        execute("POST", "/collections/" + collection + "/points/delete?wait=true", body);
    }

    public static String pointId(Long mediaId, long startMs, long endMs) {
        String value = mediaId + ":" + startMs + ":" + endMs;
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private Map<String, Object> payload(Long mediaId, VideoChunk chunk) {
        String transcript = chunk.rawSegments().stream()
                .map(VideoContext.VideoSegment::transcript)
                .filter(value -> value != null && !value.isBlank())
                .reduce("", (left, right) -> left.isBlank() ? right : left + " " + right);
        List<String> ocrTexts = chunk.rawSegments().stream()
                .flatMap(segment -> segment.ocrTexts().stream())
                .filter(value -> value != null && !value.isBlank())
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mediaId", mediaId);
        payload.put("startMs", chunk.startTime());
        payload.put("endMs", chunk.endTime());
        payload.put("source", "VIDEO_CHUNK");
        payload.put("snippet", chunk.segmentSummary());
        payload.put("transcript", transcript);
        payload.put("ocrTexts", ocrTexts);
        return payload;
    }

    private void ensureCollection(int dimension) {
        JsonNode existing = execute("GET", "/collections/" + collection, null);
        if (existing != null || dimension <= 0) return;
        Map<String, Object> vectors = Map.of("size", dimension, "distance", "Cosine");
        execute("PUT", "/collections/" + collection, Map.of("vectors", vectors));
    }

    private JsonNode execute(String method, String path, Object body) {
        try {
            Request.Builder builder = new Request.Builder().url(baseUrl + path);
            if (!apiKey.isBlank()) builder.addHeader("api-key", apiKey);
            if (body == null) {
                builder.method(method, null);
            } else {
                builder.method(method, RequestBody.create(objectMapper.writeValueAsBytes(body), JSON));
            }
            try (Response response = client.newCall(builder.build()).execute()) {
                String content = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    if (method.equals("GET") && response.code() == 404) return null;
                    throw new IllegalStateException("HTTP " + response.code() + ": " + content);
                }
                return content.isBlank() ? objectMapper.createObjectNode()
                        : objectMapper.readTree(content);
            }
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) throw stateException;
            throw new IllegalStateException("Qdrant HTTP request failed", exception);
        }
    }

    private String trimSlash(String value) {
        if (value == null || value.isBlank()) return "http://localhost:6333";
        return value.replaceAll("/+$", "");
    }

    public record VectorHit(long startMs,
                            long endMs,
                            String source,
                            String snippet,
                            String transcript,
                            List<String> ocrTexts,
                            double semanticScore) {
        public VectorHit {
            source = source == null ? "UNKNOWN" : source;
            snippet = snippet == null ? "" : snippet;
            transcript = transcript == null ? "" : transcript;
            ocrTexts = ocrTexts == null ? List.of() : List.copyOf(ocrTexts);
        }
    }
}
