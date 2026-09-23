package com.ruleup.ruleup_backend.verification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 인증 이의 제출 (POST /api/v1/verifications/{verificationId}/appeals).
 * 결정적인 형식 요건만 검사하므로 필드도 둘뿐이다.
 */
@Schema(name = "AppealSubmitRequest", description = "인증 이의 제출")
public record AppealSubmitRequest(

        @Schema(description = """
                이의 사유. **필수이며 10자 이상**이어야 한다. 미달이면 400 INVALID_REASON 으로 접수되지 않는다.
                내용의 진위는 판단하지 않는다 — 형식만 본다.""",
                example = "지하철 구간에서 GPS가 끊겨 체류 기록이 누락됐어요",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String reason,

        @Schema(description = """
                증빙 사진 URL(선택). POST /api/v1/appeals/images 응답값을 그대로 넣는다.
                진위 확인에 쓰지 않으며, 없어도 인용에 아무 영향이 없다.""",
                example = "https://cdn.ruleup.app/uploads/9f1c....png")
        String imageUrl) {}
