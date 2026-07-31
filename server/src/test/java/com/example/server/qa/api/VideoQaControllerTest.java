package com.example.server.qa.api;

import com.example.server.entity.User;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.UserMapper;
import com.example.server.qa.service.LibraryQaService;
import com.example.server.qa.service.VideoQaService;
import com.example.server.security.JwtAuthenticationFilter;
import com.example.server.security.JwtService;
import com.example.server.security.RestAccessDeniedHandler;
import com.example.server.security.RestAuthenticationEntryPoint;
import com.example.server.security.SecurityConfig;
import com.example.server.task.persistence.AnalysisTaskFailureMapper;
import com.example.server.task.persistence.AnalysisTaskMapper;
import com.example.server.task.persistence.OutboxEventMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VideoQaController.class)
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
class VideoQaControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    @MockBean
    private VideoQaService videoQaService;
    @MockBean
    private LibraryQaService libraryQaService;
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
    void answersQuestionForOwnedMedia() throws Exception {
        when(videoQaService.answer(eq(9L), eq(3L), eq(new VideoQaRequest(null, "第三章讲了什么？"))))
                .thenReturn(new VideoQaResponse(
                        "conversation-1",
                        "第三章主要讲评分规则。",
                        List.of(new VideoQaCitationView(120000L, "ASR", "这里介绍评分规则"))
                ));

        mockMvc.perform(post("/media/9/qa")
                        .contentType("application/json")
                        .content("""
                                {"question":"第三章讲了什么？"}
                                """)
                        .header("Authorization", "Bearer " + tokenFor(3L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value("conversation-1"))
                .andExpect(jsonPath("$.data.answer").value("第三章主要讲评分规则。"))
                .andExpect(jsonPath("$.data.citations[0].timestampMs").value(120000))
                .andExpect(jsonPath("$.data.citations[0].source").value("ASR"));
    }

    @Test
    void rejectsBlankQuestion() throws Exception {
        mockMvc.perform(post("/media/9/qa")
                        .contentType("application/json")
                        .content("""
                                {"question":" "}
                                """)
                        .header("Authorization", "Bearer " + tokenFor(3L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/media/9/qa")
                        .contentType("application/json")
                        .content("""
                                {"question":"第三章讲了什么？"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    private String tokenFor(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername("user-" + userId);
        user.setRole("USER");
        return jwtService.generateToken(user);
    }
}
