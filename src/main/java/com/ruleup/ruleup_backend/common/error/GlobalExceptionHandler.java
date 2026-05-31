package com.ruleup.ruleup_backend.common.error;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus())
                .body(ApiResponse.fail(ErrorResponse.of(code)));
    }

    // 본문 JSON이 깨졌거나 형식이 안 맞을 때 → 400
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        ErrorCode code = ErrorCode.INVALID_REQUEST;
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.fail(ErrorResponse.of(code)));
    }

    // 없는 경로/파일 → 404 (본문 없음). 만능 핸들러가 500으로 삼키지 않도록 구체 처리.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        ErrorCode code = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(code.getStatus())
                .body(ApiResponse.fail(ErrorResponse.of(code)));
    }

    // 멀티파트 용량 초과 → 413
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooLarge(MaxUploadSizeExceededException e) {
        ErrorCode code = ErrorCode.IMAGE_TOO_LARGE;
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.fail(ErrorResponse.of(code)));
    }
}