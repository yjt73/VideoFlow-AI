package com.example.server.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.redisson.client.RedisException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("请求参数校验失败");
        return error(ErrorCode.BAD_REQUEST, message);
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class
    })
    ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception exception) {
        return error(ErrorCode.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
        return error(exception.getErrorCode(), exception.getMessage());
    }

    @ExceptionHandler({
            RedisConnectionFailureException.class,
            RedisSystemException.class,
            RedisException.class
    })
    ResponseEntity<ApiResponse<Void>> handleRedisFailure(RuntimeException exception) {
        return error(ErrorCode.DEPENDENCY_UNAVAILABLE, "Redis 暂时不可用");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException exception) {
        return error(ErrorCode.FORBIDDEN, exception.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException exception) {
        ErrorCode errorCode = switch (exception.getStatusCode().value()) {
            case 400 -> ErrorCode.BAD_REQUEST;
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 409 -> ErrorCode.CONFLICT;
            case 429 -> ErrorCode.RATE_LIMITED;
            case 503 -> ErrorCode.DEPENDENCY_UNAVAILABLE;
            default -> ErrorCode.INTERNAL_ERROR;
        };
        return error(errorCode, exception.getReason());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("Unhandled request failure", exception);
        return error(ErrorCode.INTERNAL_ERROR, "服务器内部错误");
    }

    private ResponseEntity<ApiResponse<Void>> error(ErrorCode errorCode, String message) {
        String safeMessage = message == null || message.isBlank() ? errorCode.name() : message;
        return ResponseEntity.status(errorCode.status())
                .body(ApiResponse.error(errorCode, safeMessage));
    }
}
