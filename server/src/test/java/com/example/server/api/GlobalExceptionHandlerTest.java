package com.example.server.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.redisson.client.RedisConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void validationErrorHasUnifiedStructure() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void businessAndDependencyErrorsUseExpectedStatuses() throws Exception {
        assertError("/test/not-found", 404);
        assertError("/test/rate-limited", 429);
        assertError("/test/dependency", 503);
        assertError("/test/redis", 503);
        assertError("/test/redisson", 503);
        assertError("/test/internal", 500);
    }

    @Test
    void uploadSessionExpirationHasStableBusinessCode() throws Exception {
        mockMvc.perform(get("/test/upload-session-expired"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_SESSION_EXPIRED"));
    }

    private void assertError(String path, int expectedStatus) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedStatus))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @RestController
    static class FailureController {

        @PostMapping("/test/validate")
        void validate(@Valid @RequestBody TestRequest request) {
        }

        @GetMapping("/test/not-found")
        void notFound() {
            throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在");
        }

        @GetMapping("/test/rate-limited")
        void rateLimited() {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "请求过于频繁");
        }

        @GetMapping("/test/dependency")
        void dependency() {
            throw new DependencyUnavailableException("依赖不可用");
        }

        @GetMapping("/test/redis")
        void redis() {
            throw new RedisConnectionFailureException("redis unavailable");
        }

        @GetMapping("/test/redisson")
        void redisson() {
            throw new RedisConnectionException("redisson unavailable");
        }

        @GetMapping("/test/upload-session-expired")
        void uploadSessionExpired() {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_EXPIRED, "上传会话已过期");
        }

        @GetMapping("/test/internal")
        void internal() {
            throw new IllegalStateException("unexpected");
        }
    }

    record TestRequest(@NotBlank String name) {
    }
}
