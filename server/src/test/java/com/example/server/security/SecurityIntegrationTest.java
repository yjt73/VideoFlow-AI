package com.example.server.security;

import com.example.server.controller.MediaController;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.UserMapper;
import com.example.server.service.MediaService;
import com.example.server.storage.service.StorageAccessService;
import com.example.server.storage.service.StorageReferenceDeletionService;
import com.example.server.storage.service.ServerSideObjectIngestionService;
import com.example.server.task.persistence.AnalysisTaskFailureMapper;
import com.example.server.task.persistence.AnalysisTaskMapper;
import com.example.server.task.persistence.OutboxEventMapper;
import com.example.server.utils.YtDlpUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(MediaController.class)
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
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private MediaFileMapper mediaFileMapper;

    @MockBean
    private UserMapper userMapper;

    @MockBean
    private StringRedisTemplate redisTemplate;


    @MockBean
    private YtDlpUtils ytDlpUtils;

    @MockBean
    private MediaService mediaService;
    @MockBean
    private StorageAccessService storageAccessService;
    @MockBean
    private StorageReferenceDeletionService referenceDeletionService;
    @MockBean
    private ServerSideObjectIngestionService ingestionService;
    @MockBean
    private AnalysisTaskMapper analysisTaskMapper;
    @MockBean
    private OutboxEventMapper outboxEventMapper;
    @MockBean
    private AnalysisTaskFailureMapper analysisTaskFailureMapper;

    @Test
    void protectedEndpointRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(get("/media/list"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void userCannotDeleteAnotherUsersMedia() throws Exception {
        doThrow(new AccessDeniedException("无权删除他人的媒体"))
                .when(referenceDeletionService)
                .deleteOwnedMedia(99L, 1L);

        mockMvc.perform(delete("/media/delete")
                        .param("id", "99")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void userCannotCompleteAnotherUsersUpload() throws Exception {
        doThrow(new AccessDeniedException("forbidden"))
                .when(mediaService).completeChunkedUpload("other-upload", 1L);

        mockMvc.perform(post("/media/complete-upload")
                        .param("uploadId", "other-upload")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    private String tokenFor(Long userId) {
        com.example.server.entity.User user = new com.example.server.entity.User();
        user.setId(userId);
        user.setUsername("user-" + userId);
        user.setRole("USER");
        return jwtService.generateToken(user);
    }
}
