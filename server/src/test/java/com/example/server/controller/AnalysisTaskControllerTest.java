package com.example.server.controller;

import com.example.server.dto.AnalysisTaskCreatedView;
import com.example.server.dto.AnalysisTaskView;
import com.example.server.dto.TaskCommandView;
import com.example.server.entity.User;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.UserMapper;
import com.example.server.security.JwtAuthenticationFilter;
import com.example.server.security.JwtService;
import com.example.server.security.RestAccessDeniedHandler;
import com.example.server.security.RestAuthenticationEntryPoint;
import com.example.server.security.SecurityConfig;
import com.example.server.task.domain.AnalysisTaskStatus;
import com.example.server.task.domain.AnalysisTaskType;
import com.example.server.task.persistence.AnalysisTaskMapper;
import com.example.server.task.persistence.OutboxEventMapper;
import com.example.server.service.AnalysisTaskService;
import com.example.server.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalysisTaskController.class)
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
class AnalysisTaskControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    @MockBean
    private AnalysisTaskService taskService;
    @MockBean
    private AuthService authService;
    @MockBean
    private UserMapper userMapper;
    @MockBean
    private MediaFileMapper mediaFileMapper;
    @MockBean
    private AnalysisTaskMapper analysisTaskMapper;
    @MockBean
    private OutboxEventMapper outboxEventMapper;

    @Test
    void createsATaskAndReturnsItsGlobalId() throws Exception {
        when(taskService.create(7L, 3L, "总结关键结论")).thenReturn(
                new AnalysisTaskCreatedView(
                        "550e8400-e29b-41d4-a716-446655440000",
                        AnalysisTaskStatus.PENDING,
                        LocalDateTime.of(2026, 7, 28, 12, 0)
                )
        );

        mockMvc.perform(post("/media/7/analysis-tasks")
                        .contentType("application/json")
                        .content("""
                                {"userGoal":"总结关键结论"}
                                """)
                        .header("Authorization", "Bearer " + tokenFor(3L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId")
                        .value("550e8400-e29b-41d4-a716-446655440000"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void returnsOnlyTheAuthenticatedUsersTask() throws Exception {
        when(taskService.findOwned(
                "550e8400-e29b-41d4-a716-446655440000",
                3L
        )).thenReturn(new AnalysisTaskView(
                "550e8400-e29b-41d4-a716-446655440000",
                7L,
                AnalysisTaskType.VIDEO_ANALYSIS,
                AnalysisTaskStatus.RUNNING,
                30,
                "ANALYZING",
                0,
                null,
                null,
                null,
                LocalDateTime.of(2026, 7, 28, 12, 0),
                LocalDateTime.of(2026, 7, 28, 12, 1),
                null,
                LocalDateTime.of(2026, 7, 28, 12, 1)
        ));

        mockMvc.perform(get(
                        "/tasks/550e8400-e29b-41d4-a716-446655440000"
                ).header("Authorization", "Bearer " + tokenFor(3L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mediaId").value(7))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.progress").value(30));
    }

    @Test
    void requiresAuthenticationToQueryATask() throws Exception {
        mockMvc.perform(get(
                        "/tasks/550e8400-e29b-41d4-a716-446655440000"
                ))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exposesOwnerRetryAndCancelCommands() throws Exception {
        when(taskService.retry(
                "550e8400-e29b-41d4-a716-446655440000",
                3L
        )).thenReturn(new TaskCommandView(
                "550e8400-e29b-41d4-a716-446655440000",
                AnalysisTaskStatus.PENDING
        ));
        when(taskService.cancel(
                "660e8400-e29b-41d4-a716-446655440000",
                3L
        )).thenReturn(new TaskCommandView(
                "660e8400-e29b-41d4-a716-446655440000",
                AnalysisTaskStatus.CANCELLED
        ));

        mockMvc.perform(post(
                        "/tasks/550e8400-e29b-41d4-a716-446655440000/retry"
                ).header("Authorization", "Bearer " + tokenFor(3L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        mockMvc.perform(post(
                        "/tasks/660e8400-e29b-41d4-a716-446655440000/cancel"
                ).header("Authorization", "Bearer " + tokenFor(3L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    private String tokenFor(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername("user-" + userId);
        user.setRole("USER");
        return jwtService.generateToken(user);
    }
}
