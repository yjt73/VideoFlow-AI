package com.example.server.controller;

import com.example.server.dto.UploadCheckResponse;
import com.example.server.dto.UploadState;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.UserMapper;
import com.example.server.security.JwtAuthenticationFilter;
import com.example.server.security.JwtService;
import com.example.server.security.RestAccessDeniedHandler;
import com.example.server.security.RestAuthenticationEntryPoint;
import com.example.server.security.SecurityConfig;
import com.example.server.service.MediaService;
import com.example.server.storage.api.MediaAccessView;
import com.example.server.storage.service.StorageAccessService;
import com.example.server.task.persistence.AnalysisTaskFailureMapper;
import com.example.server.task.persistence.AnalysisTaskMapper;
import com.example.server.task.persistence.OutboxEventMapper;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.YtDlpUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class MediaUploadControllerTest {

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
    private MinioUtils minioUtils;
    @MockBean
    private YtDlpUtils ytDlpUtils;
    @MockBean
    private MediaService mediaService;
    @MockBean
    private StorageAccessService storageAccessService;
    @MockBean
    private AnalysisTaskMapper analysisTaskMapper;
    @MockBean
    private OutboxEventMapper outboxEventMapper;
    @MockBean
    private AnalysisTaskFailureMapper analysisTaskFailureMapper;

    @Test
    void checksUploadForAuthenticatedUser() throws Exception {
        when(mediaService.checkUpload(any(), eq(1L))).thenReturn(
                new UploadCheckResponse(UploadState.NEW, "upload-1", Set.of(), false, null)
        );

        mockMvc.perform(post("/media/check-upload")
                        .contentType("application/json")
                        .content("""
                                {
                                  "filename": "demo.mp4",
                                  "fileMd5": "5d41402abc4b2a76b9719d911017c592",
                                  "totalChunks": 3
                                }
                                """)
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("NEW"))
                .andExpect(jsonPath("$.data.uploadId").value("upload-1"))
                .andExpect(jsonPath("$.data.uploadedChunks").isArray())
                .andExpect(jsonPath("$.data.deduplicated").value(false));
    }

    @Test
    void rejectsInvalidFileMd5() throws Exception {
        mockMvc.perform(post("/media/check-upload")
                        .contentType("application/json")
                        .content("""
                                {
                                  "filename": "demo.mp4",
                                  "fileMd5": "invalid",
                                  "totalChunks": 3
                                }
                                """)
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void requiresChunkMd5WhenUploadingAChunk() throws Exception {
        mockMvc.perform(multipart("/media/upload-chunk")
                        .file("file", "hello".getBytes())
                        .param("uploadId", "550e8400-e29b-41d4-a716-446655440001")
                        .param("chunkIndex", "0")
                        .param("totalChunks", "1")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void completeUploadReturnsThePersistedMedia() throws Exception {
        MediaFile media = new MediaFile();
        media.setId(9L);
        media.setUserId(1L);
        media.setFilename("demo.mp4");
        media.setContentHash("5d41402abc4b2a76b9719d911017c592");
        when(mediaService.completeChunkedUpload("upload-1", 1L)).thenReturn(media);

        mockMvc.perform(post("/media/complete-upload")
                        .param("uploadId", "upload-1")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.contentHash")
                        .value("5d41402abc4b2a76b9719d911017c592"));
    }

    @Test
    void authenticatedUserCanCancelOwnUpload() throws Exception {
        mockMvc.perform(delete("/media/upload")
                        .param("uploadId", "upload-1")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("上传任务已取消"));

        verify(mediaService).cancelUpload("upload-1", 1L);
    }

    @Test
    void mediaListDoesNotExposeStorageLocation() throws Exception {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get("media:list:user:1")).thenReturn(null);
        MediaFile media = new MediaFile();
        media.setId(9L);
        media.setUserId(1L);
        media.setFilename("demo.mp4");
        media.setFilePath("http://minio/media/demo.mp4");
        media.setObjectId(8L);
        when(mediaFileMapper.selectList(any())).thenReturn(List.of(media));

        mockMvc.perform(get("/media/list")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(9))
                .andExpect(jsonPath("$.data[0].filePath").doesNotExist())
                .andExpect(jsonPath("$.data[0].objectId").doesNotExist())
                .andExpect(jsonPath("$.data[0].objectKey").doesNotExist());
    }

    @Test
    void ownerCanRequestShortLivedAccessUrl() throws Exception {
        when(storageAccessService.accessUrl(9L, 1L)).thenReturn(
                new MediaAccessView(
                        "http://localhost:9000/media/signed",
                        LocalDateTime.of(2026, 7, 29, 12, 0)
                )
        );

        mockMvc.perform(get("/media/9/access-url")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url")
                        .value("http://localhost:9000/media/signed"));
    }

    private String tokenFor(Long userId) {
        com.example.server.entity.User user = new com.example.server.entity.User();
        user.setId(userId);
        user.setUsername("user-" + userId);
        user.setRole("USER");
        return jwtService.generateToken(user);
    }
}
