package com.example.server.qa.api;

import com.example.server.api.ApiResponse;
import com.example.server.auth.AuthenticatedUser;
import com.example.server.qa.service.LibraryQaService;
import com.example.server.qa.service.VideoQaStreamService;
import com.example.server.qa.service.VideoQaService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
public class VideoQaController {

    private final VideoQaService videoQaService;
    private final LibraryQaService libraryQaService;
    private final VideoQaStreamService videoQaStreamService;

    public VideoQaController(VideoQaService videoQaService,
                             LibraryQaService libraryQaService,
                             VideoQaStreamService videoQaStreamService) {
        this.videoQaService = videoQaService;
        this.libraryQaService = libraryQaService;
        this.videoQaStreamService = videoQaStreamService;
    }

    @PostMapping("/media/{mediaId}/qa")
    public ApiResponse<VideoQaResponse> answer(
            @PathVariable Long mediaId,
            @Valid @RequestBody VideoQaRequest request,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ApiResponse.success(videoQaService.answer(mediaId, currentUser.userId(), request));
    }

    @PostMapping("/qa/library")
    public ApiResponse<LibraryQaResponse> answerLibrary(
            @Valid @RequestBody LibraryQaRequest request,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ApiResponse.success(libraryQaService.answer(currentUser.userId(), request));
    }

    @GetMapping(value = "/media/{mediaId}/qa-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter answerStream(
            @PathVariable Long mediaId,
            @RequestParam String question,
            @RequestParam(required = false) String conversationId,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) throws IOException {
        SseEmitter emitter = new SseEmitter(120_000L);
        try {
            for (VideoQaStreamEvent event : videoQaStreamService.buildEvents(
                    mediaId,
                    currentUser.userId(),
                    new VideoQaRequest(conversationId, question)
            )) {
                emitter.send(SseEmitter.event().name(event.type()).data(event));
            }
            emitter.complete();
        } catch (Exception exception) {
            emitter.send(SseEmitter.event().name("error").data(new VideoQaStreamEvent(
                    "error",
                    exception.getMessage(),
                    null
            )));
            emitter.completeWithError(exception);
        }
        return emitter;
    }
}
