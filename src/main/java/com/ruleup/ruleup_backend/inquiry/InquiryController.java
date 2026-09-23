package com.ruleup.ruleup_backend.inquiry;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.inquiry.dto.InquiryDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * CS 문의 — 앱 내 문의 단일 채널(앱 운영 정책 § 5).
 *
 * <p>이메일·SNS 등 외부 채널을 운영하지 않으므로 <b>이 경로가 유일한 창구</b>다. 접수된 건은
 * 운영자 콘솔의 {@code /api/v1/admin/inquiries} 큐로 그대로 이어진다.
 */
@Tag(name = "Inquiry", description = "CS 문의 — 접수와 열람. 답변 등록이 곧 종결이며 재문의 경로가 없다")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/inquiries")
@RequiredArgsConstructor
public class InquiryController {

    private final InquiryService service;

    @Operation(summary = "문의 접수", description = """
            **카테고리 1개 + 본문**의 2단계다. 그 밖의 폼 항목을 두지 않는다.

            - 본문 **10자 이상 1,000자 이하**, 이미지 **최대 3장**
            - 앱·OS 버전 · 기기 모델 · 오류 로그 ID 는 **고지 후 필수 자동 첨부**라 토글이 없다
            - 하루 **3건**까지 접수할 수 있다(429 `INQUIRY_DAILY_LIMIT`)

            **계정이 잠긴 상태에서도 접수된다.** 로그인 정지 중 허용 행위는 열람과 CS 문의뿐이고,
            제재 재검토가 이 채널로 들어오므로 제재가 이 경로를 막으면 다툴 방법이 사라진다.

            답변이 등록되면 알림으로 통지한다 — 분류 일반 · 토글 계정 · 야간에는 다음 날 08:00 발송.
            """)
    @ApiErrorCodes({ErrorCode.INQUIRY_BODY_LENGTH, ErrorCode.INQUIRY_IMAGE_LIMIT,
            ErrorCode.INQUIRY_DAILY_LIMIT, ErrorCode.INVALID_REQUEST, ErrorCode.LOGIN_REQUIRED})
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InquiryDtos.CreateResponse> create(@AuthenticationPrincipal String userId,
                                                          @RequestBody InquiryDtos.CreateRequest request) {
        return ApiResponse.ok(service.create(UUID.fromString(userId), request));
    }

    @Operation(summary = "내 문의 내역", description = """
            최신순. 상태 칩(`RECEIVED` · `ANSWERED`)과 새 답변 배지를 이 응답으로 그린다.

            분류는 **현재 분류**를 내린다 — 운영자가 바꿨더라도 그 사실은 알리지 않는다.
            """)
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED})
    @GetMapping
    public ApiResponse<InquiryDtos.ListResponse> list(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(service.list(UUID.fromString(userId)));
    }

    @Operation(summary = "문의 상세", description = """
            **열람 전용**이다. 답변 이후 이 스레드에 글을 추가하는 경로를 두지 않으며, 같은 사안을
            다시 묻고 싶으면 새 문의로 접수한다 — 새 문의에도 같은 SLA 가 적용된다.
            """)
    @ApiErrorCodes({ErrorCode.INQUIRY_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/{inquiryId}")
    public ApiResponse<InquiryDtos.Detail> detail(@AuthenticationPrincipal String userId,
                                                   @PathVariable UUID inquiryId) {
        return ApiResponse.ok(service.detail(UUID.fromString(userId), inquiryId));
    }
}
