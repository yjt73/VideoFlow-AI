package com.example.server.controller;

import com.example.server.entity.MediaFile;
import com.example.server.entity.User;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.UserMapper;
import com.example.server.security.JwtAuthenticationFilter;
import com.example.server.security.JwtService;
import com.example.server.security.RestAccessDeniedHandler;
import com.example.server.security.RestAuthenticationEntryPoint;
import com.example.server.security.SecurityConfig;
import com.example.server.dto.MediaAccessView;
import com.example.server.service.ServerSideObjectIngestionService;
import com.example.server.service.AuthService;
import com.example.server.service.ChunkUploadService;
import com.example.server.service.MediaIngestService;
import com.example.server.service.MediaService;
import com.example.server.service.StorageAccessService;
import com.example.server.service.StorageReferenceDeletionService;
import com.example.server.task.persistence.AnalysisTaskMapper;
import com.example.server.task.persistence.OutboxEventMapper;
import com.example.server.utils.YtDlpUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
@ActiveProfiles("legacy-persistence")
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

    @Test
    void referenceChunkUploadEndpointsAreAvailable() throws Exception {
        String token = "Bearer " + tokenFor(1L);
        when(authService.resolveUser(anyString())).thenReturn(1L);
        when(chunkUploadService.initialize("demo.mp4", 1, 1L)).thenReturn("upload-1");
        when(chunkUploadService.complete("upload-1", 1L)).thenReturn(new MediaFile());

        mockMvc.perform(post("/media/init-upload")
                        .param("filename", "demo.mp4")
                        .param("fileMd5", "5d41402abc4b2a76b9719d911017c592")
                        .param("totalChunks", "1")
                        .header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/media/check-upload")
                        .contentType("application/json")
                        .content("{}")
                        .header("Authorization", token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/media/upload-status")
                        .param("uploadId", "upload-1")
                        .header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(multipart("/media/upload-chunk")
                        .file("file", "hello".getBytes())
                        .param("uploadId", "upload-1")
                        .param("chunkIndex", "0")
                        .param("totalChunks", "1")
                        .param("chunkMd5", "5d41402abc4b2a76b9719d911017c592")
                        .header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/media/complete-upload")
                        .param("uploadId", "upload-1")
                        .header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/media/not-existing")
                        .param("uploadId", "upload-1")
                        .header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void mediaListDoesNotExposeStorageLocation() throws Exception {
        @SuppressWarnings("unchecked")
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
        MediaFile media = new MediaFile();
        media.setId(9L);
        media.setUserId(1L);
        media.setFilePath("media/demo.mp4");
        when(mediaService.requireOwnedMedia(9L, 1L)).thenReturn(media);
        when(mediaService.readableSource("media/demo.mp4"))
                .thenReturn("http://localhost:9000/media/signed");

        mockMvc.perform(get("/media/9/access-url")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url")
                        .value("http://localhost:9000/media/signed"));
    }

    private String tokenFor(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername("user-" + userId);
        user.setRole("USER");
        return jwtService.generateToken(user);
    }
}
