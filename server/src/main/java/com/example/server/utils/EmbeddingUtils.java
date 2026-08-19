package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.service.ThirdPartyApiException;
import com.example.server.service.ThirdPartyRetryConfig;
import com.example.server.service.ThirdPartyRetryExecutor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class EmbeddingUtils {

    @Value("${ai.deepseek.api-key}")
    private String apiKey;

    @Value("${ai.deepseek.base-url}")
    private String baseUrl;

    @Value("${ai.embedding.model:BAAI/bge-m3}")
    private String model;

    private final ThirdPartyRetryExecutor retryExecutor;
    private final OkHttpClient client;

    public EmbeddingUtils(ThirdPartyRetryExecutor retryExecutor) {
        this.retryExecutor = retryExecutor;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public List<Double> embed(String text) {
        return retryExecutor.execute("EMBEDDING", null, () -> doEmbed(text));
    }

    public String model() {
        return model;
    }

    private List<Double> doEmbed(String text) throws Exception {
        JSONObject requestJson = new JSONObject();
        requestJson.put("model", model);
        requestJson.put("input", text);

        Request request = new Request.Builder()
                .url(baseUrl + "/embeddings")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(
                        requestJson.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                ))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new ThirdPartyApiException(
                        "EMBEDDING",
                        response.code(),
                        "Embedding API failed: " + response.code(),
                        ThirdPartyRetryConfig.retryAfterSeconds(response.header("Retry-After"))
                );
            }
            JSONObject json = JSON.parseObject(response.body().string());
            JSONArray values = json.getJSONArray("data")
                    .getJSONObject(0)
                    .getJSONArray("embedding");
            List<Double> embedding = new ArrayList<>(values.size());
            for (Object value : values) {
                embedding.add(((Number) value).doubleValue());
            }
            return embedding;
        }
    }
}
