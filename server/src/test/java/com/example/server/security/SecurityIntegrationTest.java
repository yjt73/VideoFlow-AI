package com.example.server.security;

import com.example.server.controller.MediaController;
import com.example.server.controller.HealthController;
import com.example.server.controller.AnalysisController;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStatus;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.UserMapper;
import com.example.server.service.StorageAccessService;
import com.example.server.service.StorageReferenceDeletionService;
import com.example.server.service.ServerSideObjectIngestionService;
import com.example.server.service.AuthService;
import com.example.server.service.ChunkUploadService;
import com.example.server.service.MediaIngestService;
import com.example.server.service.MediaService;
import com.example.server.service.AiService;
import com.example.server.service.AnalysisDispatchService;
import com.example.server.service.AnalysisStatusService;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.AgentEvaluationService;
import com.example.server.service.AgentTelemetry;
import com.example.server.service.TaskEventService;
import com.example.server.service.mode.ModeRouter;
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

import java.util.concurrent.Executor;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest({MediaController.class, HealthController.class, AnalysisController.class})
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
    private AuthService authService;


    @MockBean
    private YtDlpUtils ytDlpUtils;

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
    private ChunkUploadService chunkUploadService;
    @MockBean
    private MediaIngestService mediaIngestService;
    @MockBean
    private MediaService mediaService;
    @MockBean
    private AiService aiService;
    @MockBean
    private AnalysisDispatchService analysisDispatchService;
    @MockBean
    private AnalysisStatusService analysisStatusService;
    @MockBean
    private AgentCheckpointService agentCheckpointService;
    @MockBean
    private AgentEvaluationService agentEvaluationService;
    @MockBean
    private AgentTelemetry agentTelemetry;
    @MockBean
    private TaskEventService taskEventService;
    @MockBean
    private ModeRouter modeRouter;
    @MockBean(name = "aiTaskExecutor")
    private Executor aiTaskExecutor;

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
    void healthEndpointAllowsAnonymousRequest() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("UP"));
    }

    @Test
    void activeChunkUploadRoutesAcceptAuthenticatedRequests() throws Exception {
        when(authService.resolveUser(anyString())).thenReturn(1L);
        when(chunkUploadService.initialize("clip.mp4", 2, 1L)).thenReturn("upload-1");

        mockMvc.perform(post("/media/init-upload")
                        .param("filename", "clip.mp4")
                        .param("totalChunks", "2")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("upload-1"));

        mockMvc.perform(delete("/media/upload")
                        .param("uploadId", "upload-1")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk());
        verify(chunkUploadService).cancel("upload-1", 1L);
    }

    @Test
    void analysisStatusReceivesUserIdFromJwt() throws Exception {
        when(analysisStatusService.current(9L, "分析视频", AnalysisMode.GENERAL))
                .thenReturn(TaskStatus.of(TaskStatus.State.QUEUED, "任务已排队"));

        mockMvc.perform(get("/analysis/analysis-status")
                        .param("id", "9")
                        .param("goal", "分析视频")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("QUEUED"));
    }

    @Test
    void analysisAcceptsCustomGoalButRejectsModeSelection() throws Exception {
        var media = new com.example.server.entity.MediaFile();
        media.setId(9L);
        media.setUserId(1L);
        when(mediaService.requireOwnedMedia(9L, 1L)).thenReturn(media);
        when(analysisDispatchService.submit(media, "用户目标", null, AnalysisMode.GENERAL))
                .thenReturn(AnalysisDispatchService.SubmissionResult.ACCEPTED);

        mockMvc.perform(post("/analysis/ai")
                        .param("id", "9")
                        .param("goal", " 用户目标 ")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isAccepted());
        verify(analysisDispatchService).submit(media, "用户目标", null, AnalysisMode.GENERAL);

        mockMvc.perform(post("/analysis/ai")
                        .param("id", "9")
                        .param("mode", "REVIEW")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void userCannotDeleteAnotherUsersMedia() throws Exception {
        when(authService.resolveUser(anyString())).thenReturn(1L);
        doThrow(new AccessDeniedException("无权删除他人的媒体"))
                .when(mediaService)
                .deleteOwnedMedia(99L, 1L);

        mockMvc.perform(delete("/media/delete")
                        .param("id", "99")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    private String tokenFor(Long userId) {
        com.example.server.entity.User user = new com.example.server.entity.User();
        user.setId(userId);
        user.setUsername("user-" + userId);
        user.setRole("USER");
        return jwtService.generateToken(user);
    }
}
