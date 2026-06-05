package com.ruleup.ruleup_backend.common.response;

import com.ruleup.ruleup_backend.common.error.ErrorResponse;

/**
 * 모든 API의 공통 응답 봉투.
 * 성공: { "success": true,  "data": {...}, "error": null }
 * 실패: { "success": false, "data": null, "error": { code, message } }
 * (성공/실패와 무관하게 HTTP 상태코드는 실제대로 유지한다.)
 */
public record ApiResponse<T>(boolean success, T data, ErrorResponse error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> ok() {        // 본문 없는 성공(있어야 할 때)
        return new ApiResponse<>(true, null, null);
    }

    public static <T> ApiResponse<T> fail(ErrorResponse error) {
        return new ApiResponse<>(false, null, error);
    }
}