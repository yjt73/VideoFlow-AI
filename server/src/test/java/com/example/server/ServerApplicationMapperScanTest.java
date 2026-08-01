package com.example.server;

import com.example.server.storage.config.StoragePersistenceConfig;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ServerApplicationMapperScanTest {

    @Test
    void serverApplicationDoesNotForceMapperCreationInWebSlices() {
        assertThat(ServerApplication.class.getAnnotation(
                MapperScan.class))
                .isNull();
    }

    @Test
    void persistenceConfigurationScansAllMapperPackages() {
        MapperScan mapperScan = StoragePersistenceConfig.class.getAnnotation(MapperScan.class);

        assertThat(mapperScan).isNotNull();
        assertThat(Arrays.asList(mapperScan.value())).containsExactlyInAnyOrder(
                "com.example.server.mapper",
                "com.example.server.task.persistence",
                "com.example.server.storage.persistence",
                "com.example.server.rag.persistence"
        );
    }
}
