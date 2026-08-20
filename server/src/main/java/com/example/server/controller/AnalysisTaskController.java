package com.example.server.controller;

import com.example.server.api.ApiResponse;
import com.example.server.dto.AnalysisTaskCreatedView;
import com.example.server.dto.AnalysisTaskView;
import com.example.server.dto.CreateAnalysisTaskRequest;
import com.example.server.dto.TaskCommandView;
import com.example.server.service.AuthenticatedUser;
import com.example.server.service.AnalysisTaskService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

/** 创建和查询全局分析任务。 */
@Profile("legacy-persistence")
@RestController
public class AnalysisTaskController {

    private final AnalysisTaskService taskService;

    public AnalysisTaskController(AnalysisTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/media/{mediaId}/analysis-tasks")
    public ApiResponse<AnalysisTaskCreatedView> create(
            @PathVariable Long mediaId,
            @Valid @RequestBody(required = false) CreateAnalysisTaskRequest request,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        String userGoal = request == null ? null : request.userGoal();
        return ApiResponse.success(
                taskService.create(mediaId, currentUser.userId(), userGoal)
        );
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<AnalysisTaskView> find(
            @PathVariable String taskId,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ApiResponse.success(
                taskService.findOwned(taskId, currentUser.userId())
        );
    }

    @PostMapping("/tasks/{taskId}/retry")
    public ApiResponse<TaskCommandView> retry(
            @PathVariable String taskId,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ApiResponse.success(
                taskService.retry(taskId, currentUser.userId())
        );
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<TaskCommandView> cancel(
            @PathVariable String taskId,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ApiResponse.success(
                taskService.cancel(taskId, currentUser.userId())
        );
    }
}
