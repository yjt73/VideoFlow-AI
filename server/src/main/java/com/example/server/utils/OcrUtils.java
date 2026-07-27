package com.example.server.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 封装关键帧图片的 OCR 文本识别。 */
@Component
public class OcrUtils {

    @Value("${tool.ocr.command:tesseract}")
    private String ocrCommand;

    /** 识别图片中的文字；识别失败时返回空文本。 */
    public String recognize(File image) {
        try {
            Process process = new ProcessBuilder(
                    ocrCommand,
                    image.getAbsolutePath(),
                    "stdout",
                    "-l",
                    "chi_sim+eng"
            ).redirectErrorStream(true).start();

            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        text.append(line).append('\n');
                    }
                }
            }
            return process.waitFor() == 0 ? text.toString().trim() : "";
        } catch (Exception e) {
            System.err.println("OCR 识别失败: " + image.getName() + ", " + e.getMessage());
            return "";
        }
    }
}
