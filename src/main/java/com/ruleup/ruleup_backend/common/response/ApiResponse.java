package com.ruleup.ruleup_backend.common.response;

import com.ruleup.ruleup_backend.common.error.ErrorResponse;

/**
 * 모든 API의 공통 응답 봉투.
 * 성공: { "success": true,  "data": {...}, "error": null }
 * 실패: { "success": false, "data": null, "error": { code, message } }
 * (성공/실패와 무관하게 HTTP 상태코드는 실제대로 유지한다.)
 */
@io.swagger.v3.oas.annotations.media.Schema(description = """
        모든 API 의 공통 응답 봉투. 성공·실패가 같은 모양이고, HTTP 상태코드는 실제 상태를 그대로 쓴다.""")
public record ApiResponse<T>(

        @io.swagger.v3.oas.annotations.media.Schema(description = "성공 여부", example = "true")
        boolean success,

        @io.swagger.v3.oas.annotations.media.Schema(description = "성공 시 결과. 본문이 없는 API 와 실패 응답에서는 null.")
        T data,

        @io.swagger.v3.oas.annotations.media.Schema(description = "실패 시 { code, message }. 성공 응답에서는 null.")
        ErrorResponse error) {

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