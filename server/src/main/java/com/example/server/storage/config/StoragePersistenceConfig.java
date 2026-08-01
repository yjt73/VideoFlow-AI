package com.example.server.storage.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan({
        "com.example.server.mapper",
        "com.example.server.task.persistence",
        "com.example.server.storage.persistence",
        "com.example.server.rag.persistence"
})
public class StoragePersistenceConfig {
}
