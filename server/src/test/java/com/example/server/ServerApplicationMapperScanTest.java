package com.example.server;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ServerApplicationMapperScanTest {

    @Test
    void serverApplicationDoesNotForceMapperCreationInWebSlices() {
        assertThat(ServerApplication.class.getAnnotation(
                org.mybatis.spring.annotation.MapperScan.class))
                .isNull();
    }
}
