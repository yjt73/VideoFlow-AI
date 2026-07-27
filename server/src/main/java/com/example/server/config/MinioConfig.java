package com.example.server.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 创建并配置用于媒体文件存储的 MinIO 客户端。 */
@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.accessKey}")
    private String accessKey;

    @Value("${minio.secretKey}")
    private String secretKey;

    @Value("${minio.bucketName}")
    private String bucketName;

    @Bean
    /** 使用配置文件中的地址和凭证创建共享 MinIO 客户端。 */
    public MinioClient minioClient() {
        try {
            MinioClient client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();

            //检查桶是否存在，不存在就创建
            boolean found = client.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                System.out.println("⚠️ MinIO 桶 [" + bucketName + "] 不存在，正在创建...");
                client.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }

            //强制设置权限为 Public (只读权限)
            String policyJson = "{\n" +
                    "  \"Version\": \"2012-10-17\",\n" +
                    "  \"Statement\": [\n" +
                    "    {\n" +
                    "      \"Effect\": \"Allow\",\n" +
                    "      \"Principal\": {\n" +
                    "        \"AWS\": [\n" +
                    "          \"*\"\n" +
                    "        ]\n" +
                    "      },\n" +
                    "      \"Action\": [\n" +
                    "        \"s3:GetObject\"\n" +
                    "      ],\n" +
                    "      \"Resource\": [\n" +
                    "        \"arn:aws:s3:::" + bucketName + "/*\"\n" +
                    "      ]\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}";

            client.setBucketPolicy(
                    SetBucketPolicyArgs.builder()
                            .bucket(bucketName)
                            .config(policyJson)
                            .build()
            );

            System.out.println("  MinIO 配置成功，桶权限已强制设置为 Public！");
            return client;

        } catch (Exception e) {
            throw new RuntimeException("MinIO 初始化失败", e);
        }
    }
}
