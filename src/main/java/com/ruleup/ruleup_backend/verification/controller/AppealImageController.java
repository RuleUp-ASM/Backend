package com.ruleup.ruleup_backend.verification.controller;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.verification.dto.AppealImageResponse;
import com.ruleup.ruleup_backend.verification.service.AppealImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 이의 증빙 사진 업로드. base = /api/v1/appeals. */
@Tag(name = "이의제기", description = "인증 이의 증빙 사진 업로드")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/appeals")
@RequiredArgsConstructor
public class AppealImageController {

    private final AppealImageService appealImageService;

    @Operation(summary = "인증 이의제기 이미지 업로드",
            description = """
                    이의에 첨부할 증빙 사진을 올리고 URL 을 받는다. 그 URL 을 이의 제출의 `imageUrl` 로 넘긴다.

                    **사진은 선택이고 진위 확인에 쓰지 않는다** — 올렸다고 인용 확률이 달라지지 않으며,
                    올리지 않아도 형식 요건만 맞으면 인용된다. 업로드 자체는 이의 접수가 아니다.
                    """)
    @ApiErrorCodes({ErrorCode.IMAGE_TOO_LARGE, ErrorCode.IMAGE_CORRUPTED, ErrorCode.LOGIN_REQUIRED})
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AppealImageResponse> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(new AppealImageResponse(appealImageService.upload(file)));
    }
}
