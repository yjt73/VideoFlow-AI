package com.example.server.storage.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.example.server.storage.persistence")
public class StoragePersistenceConfig {
}
