package com.example.server.controller;

import com.example.server.dto.UploadCheckResponse;
import com.example.server.dto.UploadState;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.UserMapper;
import com.example.server.security.JwtAuthenticationFilter;
import com.example.server.security.JwtService;
import com.example.server.security.RestAccessDeniedHandler;
import com.example.server.security.RestAuthenticationEntryPoint;
import com.example.server.security.SecurityConfig;
import com.example.server.service.MediaService;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.YtDlpUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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

    private String tokenFor(Long userId) {
        com.example.server.entity.User user = new com.example.server.entity.User();
        user.setId(userId);
        user.setUsername("user-" + userId);
        user.setRole("USER");
        return jwtService.generateToken(user);
    }
}
