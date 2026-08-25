package com.ruleup.ruleup_backend.verification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 이의 증빙 사진 업로드 결과. 이 URL 을 이의 제출의 {@code imageUrl} 로 넘긴다.
 * 사진은 선택이고 진위 확인에 쓰지 않으므로 업로드 자체가 이의 접수를 뜻하지 않는다.
 */
@Schema(name = "AppealImageResponse", description = "이의 증빙 사진 업로드 결과")
public record AppealImageResponse(
        @Schema(description = "업로드된 이미지 URL", example = "https://cdn.ruleup.app/uploads/9f1c....png")
        String imageUrl) {}
