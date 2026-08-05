package com.example.server.storage.api;

import com.example.server.entity.User;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.UserMapper;
import com.example.server.security.JwtAuthenticationFilter;
import com.example.server.security.JwtService;
import com.example.server.security.RestAccessDeniedHandler;
import com.example.server.security.RestAuthenticationEntryPoint;
import com.example.server.security.SecurityConfig;
import com.example.server.storage.service.MultipartUploadService;
import com.example.server.storage.service.MultipartCompletionService;
import com.example.server.task.persistence.AnalysisTaskFailureMapper;
import com.example.server.task.persistence.AnalysisTaskMapper;
import com.example.server.task.persistence.OutboxEventMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MultipartUploadController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtService.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class
})
@TestPropertySource(properties = {
        "security.jwt.secret=0123456789abcdef0123456789abcdef",
        "security.jwt.expiration-seconds=7200"
})
class MultipartUploadControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    @MockBean
    private MultipartUploadService service;
    @MockBean
    private MultipartCompletionService completionService;
    @MockBean
    private UserMapper userMapper;
    @MockBean
    private MediaFileMapper mediaFileMapper;
    @MockBean
    private AnalysisTaskMapper analysisTaskMapper;
    @MockBean
    private OutboxEventMapper outboxEventMapper;
    @MockBean
    private AnalysisTaskFailureMapper analysisTaskFailureMapper;

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/media/multipart/session-1/parts/2/url"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsPresignedPartForOwner() throws Exception {
        when(service.presignPart("session-1", 2, 3L)).thenReturn(
                new PresignedPartView(
                        2,
                        "http://localhost:9000/media?partNumber=2",
                        LocalDateTime.of(2026, 7, 29, 12, 0)
                )
        );

        mockMvc.perform(post("/media/multipart/session-1/parts/2/url")
                        .header("Authorization", "Bearer " + tokenFor(3L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.partNumber").value(2))
                .andExpect(jsonPath("$.data.url").value(
                        "http://localhost:9000/media?partNumber=2"
                ));
    }

    @Test
    void rejectsAnotherUsersSession() throws Exception {
        when(service.presignPart("session-1", 2, 3L))
                .thenThrow(new AccessDeniedException("无权操作该上传会话"));

        mockMvc.perform(post("/media/multipart/session-1/parts/2/url")
                        .header("Authorization", "Bearer " + tokenFor(3L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void recordsCompletedPartProgress() throws Exception {
        mockMvc.perform(post("/media/multipart/session-1/progress")
                        .header("Authorization", "Bearer " + tokenFor(3L))
                        .contentType("application/json")
                        .content("""
                                {"partNumbers":[1]}
                                """))
                .andExpect(status().isOk());

        verify(service).recordProgress(
                "session-1",
                new MultipartProgressRequest(java.util.List.of(1)),
                3L
        );
    }

    @Test
    void completesWithoutClientPartManifest() throws Exception {
        mockMvc.perform(post("/media/multipart/session-1/complete")
                        .header("Authorization", "Bearer " + tokenFor(3L)))
                .andExpect(status().isOk());

        verify(completionService).complete("session-1", 3L);
    }

    @Test
    void returnsBatchPresignedParts() throws Exception {
        when(service.presignParts("session-1", java.util.List.of(1, 2), 3L))
                .thenReturn(java.util.List.of(
                        new PresignedPartView(1, "http://localhost:9000/part-1", LocalDateTime.now()),
                        new PresignedPartView(2, "http://localhost:9000/part-2", LocalDateTime.now())
                ));

        mockMvc.perform(post("/media/multipart/session-1/parts/urls")
                        .header("Authorization", "Bearer " + tokenFor(3L))
                        .contentType("application/json")
                        .content("""
                                {"partNumbers":[1,2]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].partNumber").value(2));
    }

    private String tokenFor(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername("user-" + userId);
        user.setRole("USER");
        return jwtService.generateToken(user);
    }
}
