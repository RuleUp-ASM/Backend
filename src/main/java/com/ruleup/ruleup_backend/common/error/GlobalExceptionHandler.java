package com.ruleup.ruleup_backend.common.error;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
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
        ErrorResponse body = (e.getDetail() != null)
                ? ErrorResponse.of(code, e.getDetail(), e.getRejoinAvailableAt())
                : ErrorResponse.of(code);
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.fail(body));
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

    // 경로는 있으나 메서드가 없을 때(폐기된 API 호출 등) → 405 (만능 핸들러의 500 방지)
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Void> handleMethodNotSupported(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED).build();
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

    /**
     * DB unique 제약 위반 → 409.
     * "사전 중복검사(isNicknameTaken)는 통과했지만, 거의 동시에 같은 닉네임으로
     *  두 요청이 들어와 둘 다 검사를 통과한 뒤 INSERT한" 경쟁 상황(race condition)의 최종 방어선.
     * DB의 uq_users_active_*_nickname 제약이 늦게 들어온 쪽을 막아주고, 그걸 여기서 409로 변환한다.
     * => 결과적으로 "먼저 INSERT에 성공한 사람만" 닉네임을 가져간다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(DataIntegrityViolationException e) {
        String constraint = extractConstraint(e);
        if (constraint != null && (constraint.contains("uq_users_active_requested_nickname")
                || constraint.contains("uq_users_active_approved_nickname"))) {
            ErrorCode code = ErrorCode.NICKNAME_DUPLICATED;   // 409
            return ResponseEntity.status(code.getStatus()).body(ApiResponse.fail(ErrorResponse.of(code)));
        }
        // 닉네임 외의 무결성 위반(예: 같은 소셜계정 동시 가입 uq_users_oauth)은
        // 정상 흐름에선 거의 없어야 하므로, 원인을 남기고 500으로 처리한다.
        log.error("Data integrity violation (constraint={})", constraint, e);
        ErrorCode code = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.fail(ErrorResponse.of(code)));
    }

    /** 어떤 DB 제약이 깨졌는지 이름을 뽑아낸다. (Hibernate가 주면 그 이름, 아니면 메시지로 폴백) */
    private String extractConstraint(DataIntegrityViolationException e) {
        if (e.getCause() instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
            return cve.getConstraintName();          // 예: "uq_users_active_requested_nickname"
        }
        Throwable root = e.getMostSpecificCause();   // 폴백: 메시지 안에 제약명이 들어있는 경우
        return (root != null) ? root.getMessage() : null;
    }
}