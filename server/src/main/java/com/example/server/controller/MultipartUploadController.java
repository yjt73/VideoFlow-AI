package com.example.server.controller;

import com.example.server.api.ApiResponse;
import com.example.server.dto.CompletedPartRequest;
import com.example.server.dto.MediaAccessView;
import com.example.server.dto.MultipartCheckRequest;
import com.example.server.dto.MultipartCheckView;
import com.example.server.dto.MultipartProgressRequest;
import com.example.server.dto.PresignPartsRequest;
import com.example.server.dto.PresignedPartView;
import com.example.server.service.AuthenticatedUser;
import com.example.server.entity.MediaFile;
import com.example.server.service.MultipartCompletionService;
import com.example.server.service.MultipartUploadService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @PostMapping("/media/multipart/{sessionId}/parts/urls")
    public ApiResponse<List<PresignedPartView>> presignParts(
            @PathVariable String sessionId,
            @Valid @RequestBody PresignPartsRequest request,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ApiResponse.success(
                service.presignParts(sessionId, request.partNumbers(), currentUser.userId())
        );
    }

    @PostMapping("/media/multipart/{sessionId}/progress")
    public ApiResponse<Void> recordProgress(
            @PathVariable String sessionId,
            @Valid @RequestBody MultipartProgressRequest request,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        service.recordProgress(sessionId, request, currentUser.userId());
        return ApiResponse.success(null);
    }

    @PostMapping("/media/multipart/{sessionId}/complete")
    public ApiResponse<MediaFile> complete(
            @PathVariable String sessionId,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ApiResponse.success(
                completionService.complete(sessionId, currentUser.userId())
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
