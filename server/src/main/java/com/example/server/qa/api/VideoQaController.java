package com.example.server.qa.api;

import com.example.server.api.ApiResponse;
import com.example.server.auth.AuthenticatedUser;
import com.example.server.qa.service.VideoQaService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VideoQaController {

    private final VideoQaService videoQaService;

    public VideoQaController(VideoQaService videoQaService) {
        this.videoQaService = videoQaService;
    }

    @PostMapping("/media/{mediaId}/qa")
    public ApiResponse<VideoQaResponse> answer(
            @PathVariable Long mediaId,
            @Valid @RequestBody VideoQaRequest request,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ApiResponse.success(videoQaService.answer(mediaId, currentUser.userId(), request));
    }
}
