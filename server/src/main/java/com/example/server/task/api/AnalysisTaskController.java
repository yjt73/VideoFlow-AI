package com.example.server.task.api;

import com.example.server.api.ApiResponse;
import com.example.server.auth.AuthenticatedUser;
import com.example.server.task.service.AnalysisTaskService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 创建和查询全局分析任务。 */
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
}
