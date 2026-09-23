package com.ruleup.ruleup_backend.challenge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 방 운영 API(초대 발급 · 강퇴 · 방장 권한)의 요청/응답. */
public final class RoomAdminDtos {
    private RoomAdminDtos() {}

    @Schema(name = "InvitationIssueResponse", description = "발급된 초대 링크. token 은 이 응답에서만 볼 수 있다(서버에는 해시만 남는다).")
    public record InvitationResponse(
            @Schema(description = "초대장 id") String invitationId,
            @Schema(description = "초대 토큰 — 다시 조회할 수 없다. 잃어버리면 재발급.") String token,
            @Schema(description = "토큰이 붙은 앱 초대 링크", example = "https://android.ruleup.co.kr/c/AbC123...") String inviteUrl,
            @Schema(description = "만료 시각(발급 후 7일)", example = "2026-08-24T10:00:00Z") String expiresAt) {}

    @Schema(name = "KickRequest", description = "강퇴 요청")
    public record KickRequest(
            @Schema(description = "강퇴 사유 — **10~500자 필수**. 저장되며 당사자에게 알림으로도 전달된다.",
                    example = "반복적인 운영 규칙 위반입니다.", requiredMode = Schema.RequiredMode.REQUIRED)
            String reason) {}

    @Schema(name = "KickResponse", description = "강퇴 결과")
    public record KickResponse(
            @Schema(example = "true") boolean kicked,
            @Schema(description = "내보낸 멤버의 userId") String userId,
            @Schema(description = "그 멤버가 이 방에 다시 들어올 수 있는 시각. 강퇴가 반복될수록 두 배(1주→2주→4주).",
                    example = "2026-08-24T10:00:00Z")
            String rejoinAvailableAt) {}

    @Schema(name = "OwnerTransferRequest", description = "방장 권한 넘기기 요청")
    public record TransferRequest(
            @Schema(description = "권한을 받을 멤버의 userId. 그 방의 ACTIVE 멤버여야 한다.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String targetUserId) {}

    @Schema(name = "OwnerTransferResponse", description = "방장 권한 넘기기 결과 — 수락 절차 없이 즉시 반영된다")
    public record TransferResponse(
            @Schema(description = "새 방장의 userId") String newOwnerUserId,
            @Schema(description = "넘긴 뒤의 내 역할", example = "MEMBER") String myRole) {}

    @Schema(name = "OwnerClaimResponse", description = "봇방장 클레임 결과")
    public record ClaimResponse(
            @Schema(description = "클레임 후 내 역할", example = "OWNER") String myRole,
            @Schema(description = "감점 면책이 끝나는 시각(승계 3일). 넘겨받은 방장에게는 없는 혜택이다.",
                    example = "2026-08-20T10:00:00Z")
            String graceUntil) {}
}
