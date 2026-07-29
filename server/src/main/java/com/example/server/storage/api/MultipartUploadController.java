package com.example.server.storage.api;

import com.example.server.api.ApiResponse;
import com.example.server.auth.AuthenticatedUser;
import com.example.server.entity.MediaFile;
import com.example.server.storage.service.MultipartCompletionService;
import com.example.server.storage.service.MultipartUploadService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MultipartUploadController {

    private final MultipartUploadService service;
    private final MultipartCompletionService completionService;

    public MultipartUploadController(
            MultipartUploadService service,
            MultipartCompletionService completionService
    ) {
        this.service = service;
        this.completionService = completionService;
    }

    @PostMapping("/media/multipart/check")
    public ApiResponse<MultipartCheckView> check(
            @Valid @RequestBody MultipartCheckRequest request,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ApiResponse.success(service.check(request, currentUser.userId()));
    }

    @PostMapping("/media/multipart/{sessionId}/parts/{partNumber}/url")
    public ApiResponse<PresignedPartView> presignPart(
            @PathVariable String sessionId,
            @PathVariable int partNumber,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ApiResponse.success(
                service.presignPart(sessionId, partNumber, currentUser.userId())
        );
    }

    @PostMapping("/media/multipart/{sessionId}/complete")
    public ApiResponse<MediaFile> complete(
            @PathVariable String sessionId,
            @Valid @RequestBody CompleteMultipartRequest request,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ApiResponse.success(
                completionService.complete(sessionId, request, currentUser.userId())
        );
    }

    @DeleteMapping("/media/multipart/{sessionId}")
    public ApiResponse<Void> abort(
            @PathVariable String sessionId,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        completionService.abort(sessionId, currentUser.userId());
        return ApiResponse.success(null);
    }
}
