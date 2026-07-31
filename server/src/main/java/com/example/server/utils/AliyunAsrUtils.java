package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.ai.retry.ThirdPartyApiException;
import com.example.server.ai.retry.ThirdPartyRetryConfig;
import com.example.server.ai.retry.ThirdPartyRetryExecutor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.TimeUnit;

@Component
public class AliyunAsrUtils {

    @Value("${ai.deepseek.api-key}")
    private String apiKey;

    private final ThirdPartyRetryExecutor retryExecutor;
    private final OkHttpClient client;

    public AliyunAsrUtils(ThirdPartyRetryExecutor retryExecutor) {
        this.retryExecutor = retryExecutor;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(600, TimeUnit.SECONDS)
                .writeTimeout(600, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    public String audioToText(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            return "错误：找不到音频文件";
        }
        try {
            return retryExecutor.execute("ASR", null, () -> callAsr(file));
        } catch (Exception exception) {
            return "识别最终失败: " + exception.getMessage();
        }
    }

    private String callAsr(File file) throws Exception {
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                        "file",
                        file.getName(),
                        RequestBody.create(
                                file,
                                MediaType.parse("application/octet-stream")
                        )
                )
                .addFormDataPart("model", "TeleAI/TeleSpeechASR")
                .build();

        Request request = new Request.Builder()
                .url("https://api.siliconflow.cn/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(requestBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String body = response.body() == null ? "" : response.body().string();
                throw new ThirdPartyApiException(
                        "ASR",
                        response.code(),
                        "HTTP " + response.code() + ": " + body,
                        ThirdPartyRetryConfig.retryAfterSeconds(response.header("Retry-After"))
                );
            }
            JSONObject jsonObject = JSON.parseObject(response.body().string());
            String text = jsonObject.getString("text");
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("ASR response missing text");
            }
            return text;
        }
    }
}
